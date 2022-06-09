package com.anon;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;


import com.anon.datatype.CommitInfo;
import com.anon.cmdrunners.GitCmdRunner;
import com.anon.datatype.MethodReplacement;
import com.anon.datatype.RepositoryInfo;
import com.anonymous2.git.GitCloner;
import com.anon.helpers.InterestingCommitsLoader;
import com.anon.helpers.TargetApacheCommonsAPIHelper;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvValidationException;
import org.apache.commons.cli.*;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Output: a SQLite file with two tables:
 *          1. APIReplacements: cases where a local method is replaced with an API
 *          2. ImportChanges: For files where we identified a method2API replacement, we store changes made to import statements.
 *
 * REMINDER: Please first run the app with no arguments to receive a sample log, and make sure it follows
 *           ./src/main/resources/log4j.properties (like, it starts with "[main]", and not "CRAW")
 *           If it's not correct re-package using mvn. Magically it will be fixed!
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static final AtomicInteger totalReposProcessed = new AtomicInteger(0);
    public static int totalRepos = -1;
    public static boolean DELETE_REPO_AFTER_PROCESS = false;
    public static boolean DEBUG_MODE = false;
    public static Path REPO_LIST;
    public static Path CLONE_REPOSITORIES_AT;
    public static Path RESULT_PATH;
    public static Path PREV_RESULT_PATH;
    public static Path APIS_PATH;
    public static int N_THREADS;

    public static void main2(String[] args) {
//        List<GitCmdRunner.CodeReplacement> codeChanges = GitCmdRunner.git_diff_JustChanges(true, Path.of("/Users/emadpres/Downloads/iMRMC"),
//                "3424dae", "cfdd0dfd1b7724efbc786d0cfc070dc0696435b4",
//                Path.of("imrmc/mrmc_source/src/simroemetz/core/CofVGenRoeMetz.java"),
//                Path.of("imrmc/mrmc_source/src/simroemetz/core/CofVGenRoeMetz.java"), true);


        List<GitCmdRunner.CodeReplacement> codeChanges = new ArrayList();
        codeChanges.add(new GitCmdRunner.CodeReplacement("obj.X().X().obj.Y().foo().Z()","lib.X().lib.Y().bar().Z()",0,100));

        var v = new FindCustomImplReplacements(null);
        List<MethodReplacement> methodReplacements = v.CheckForCustomImplReplacement(new RepositoryInfo("repo", "branch"), new CommitInfo("xxxx"), new GitCmdRunner.GitFilePath(Path.of("path")), codeChanges);
    }

    public static void main(String[] args)
    {
        ParseArguments(args);

        boolean success = ResultWriter.SetupDB(RESULT_PATH);
        if(!success)
            System.exit(1);

        //InterestingCommitsLoader.LoadInterestingCommits(PREV_RESULT_PATH);
        TargetApacheCommonsAPIHelper.LoadAPIs(APIS_PATH);
        TargetApacheCommonsAPIHelper.LoadPackages(APIS_PATH);

        List<RepositoryInfo> repos = ReadListOfRepos(REPO_LIST);
//        repos = CherryPickReposBasedOnPreviousResults(repos);
        totalRepos = repos.size();

        CreateReposOwnerFolders(repos);


        Runnable r = createRunnable(CLONE_REPOSITORIES_AT, RESULT_PATH, repos);

        List<Thread> threads = new ArrayList<>(N_THREADS);
        for (int i=0; i<N_THREADS; i++)
        {
            Thread t = new Thread(r, "T"+Integer.toString(threads.size()+1));
            threads.add(t);
            t.start();
        }

        logger.info("============= All {} threads kicked off", threads.size());

        for(Thread t: threads)
        {
            try {
                logger.info("============= Waiting for thread {}(/{}) ...", t.getName(), threads.size());
                t.join();
                logger.info("============= Thread {}(/{}) finished.", t.getName(), threads.size());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        if(DELETE_REPO_AFTER_PROCESS) {
            logger.info("TODO: Clean up! Delete Owner directory with no repos inside"); //TODO
        }
        logger.info("*********************");
        logger.info("*** We're done :) ***");
        logger.info("*********************");
    }

    private static List<RepositoryInfo> CherryPickReposBasedOnPreviousResults(List<RepositoryInfo> repos) {
        List<RepositoryInfo> reposInvolvedInPreviousResults = new ArrayList<>();
        for(RepositoryInfo r: repos)
            if(InterestingCommitsLoader.getInterestingCommits().containsKey(r.GetFullName()))
                reposInvolvedInPreviousResults.add(r);
        return reposInvolvedInPreviousResults;
    }

    private static void CreateReposOwnerFolders(List<RepositoryInfo> repos) {
        // Setup repo owner directories, Why? We get into trouble when two different threads want
        //                                    to clone X/r1 and X/r2 as they face conflicts in creating parent folder.
        for(RepositoryInfo aRepoInfo: repos)
        {
            Path repo_owner_directory = CLONE_REPOSITORIES_AT.resolve(aRepoInfo.GetOwner());
            if (!Files.exists(repo_owner_directory)) {
                boolean res = repo_owner_directory.toFile().mkdirs();
                if (!res) {
                    logger.error("Failed to make {} owner directory at {}", aRepoInfo, repo_owner_directory);
                    System.exit(-1);
                }
            }
        }
    }

    private static Path AddTimeStamp(String pathStr) {
        Path res;
        String timeStamp = new SimpleDateFormat("yyyy.MM.dd-HH.mm.ss").format(new java.util.Date());
        if(pathStr.endsWith(".db") || pathStr.endsWith(".sqlite"))
        {
            int pos_ext = pathStr.lastIndexOf('.');
            String pathWithoutExtension = pathStr.substring(0,pos_ext);
            String extension = pathStr.substring(pos_ext+1);
            res = Path.of(String.format("%s-%s.%s", pathWithoutExtension, timeStamp, extension) );
        }
        else
            res = Path.of(String.format("%s-%s", pathStr, timeStamp) );
        return res;
    }

    private static Runnable createRunnable(Path CLONE_REPOSITORIES_AT, Path RESULT_PATH, List<RepositoryInfo> repos) {
        Runnable r = ()->{
            Connection thread_db_conn = null;
            ResultWriter resultWriter = null;

            // Setup a new ResultWriter for each thread
            try {
                thread_db_conn = DriverManager.getConnection("jdbc:sqlite:" + RESULT_PATH);
                PreparedStatement prep_stms_methods = ResultWriter.GetNewDBPreparedStatement_Methods(thread_db_conn);
                PreparedStatement prep_stms_imports = ResultWriter.GetNewDBPreparedStatement_Imports(thread_db_conn);
                resultWriter = new ResultWriter(prep_stms_methods, prep_stms_imports);
            } catch (SQLException e) {
                logger.error("Failed create DB connection => Exiting thread", e);
                return;
            }


            while(true) // Process repos until they are all processed
            {
                // Check if all repos are processed
                if(totalReposProcessed.get() == repos.size())
                    break; // no more tasks
                int repoIndex = totalReposProcessed.getAndIncrement();
                if(repoIndex >= repos.size()) {
                    totalReposProcessed.decrementAndGet();
                    break;  // All tasks finished
                }

                // Get a new repo to process
                RepositoryInfo aRepoInfo = repos.get(repoIndex);
                logger.info("({}/{}) {} Cloning... ", Main.totalReposProcessed.get(), Main.totalRepos, aRepoInfo);


                int cloningResult = -1;
                try {
                    cloningResult = GitCloner.CloneRepo(aRepoInfo.GetFullName(), CLONE_REPOSITORIES_AT, false, aRepoInfo.GetDefaultBranch(), false, false);
                    if(cloningResult < 0)
                        throw new IOException();

                    logger.info("({}/{}) {} Cloning FINISHED", Main.totalReposProcessed.get(), Main.totalRepos, aRepoInfo);

                    aRepoInfo.SetPath(CLONE_REPOSITORIES_AT.resolve(aRepoInfo.GetFullName()));

                    try {
                        ProcessRepository(aRepoInfo, resultWriter);
                    } catch (Exception e)
                    {
                        logger.error("({}/{}) {} Processing Repository FAILED", Main.totalReposProcessed.get(), Main.totalRepos, aRepoInfo, e);
                    }

                    if(DELETE_REPO_AFTER_PROCESS) {
                        logger.info("({}/{}) {} Deleting repository", Main.totalReposProcessed.get(), Main.totalRepos, aRepoInfo);

                        String repo_owner = aRepoInfo.GetOwner();
                        String repo_name = aRepoInfo.GetName();
                        Path repo_directory = CLONE_REPOSITORIES_AT.resolve(repo_owner).resolve(repo_name);

                        FileUtils.deleteDirectory(repo_directory.toFile());
                    }

                } catch (Exception e) {
                    if(cloningResult<0)
                        logger.error("({}/{}) {} Cloning FAILED code={}", Main.totalReposProcessed.get(), Main.totalRepos, aRepoInfo, cloningResult);
                    else
                        logger.error("({}/{}) {} Processing Repository FAILED (for unknown error)", Main.totalReposProcessed.get(), Main.totalRepos, aRepoInfo, e);
                }
            }

            try {
                thread_db_conn.close();
            } catch (SQLException e) {
                logger.error("Failed closing DB connection !!!", e);
            }
        };
        return r;
    }

    public static void ProcessRepository(RepositoryInfo aRepoInfo, ResultWriter resultWriter) {
        if(aRepoInfo.GetPath()==null || aRepoInfo.GetDefaultBranch()==null) {
            logger.error("({}/{}) {} Invalid repo data. Repo SKIPPED.", Main.totalReposProcessed.get(), Main.totalRepos, aRepoInfo);
            return;
        }

        new CommitIterator(aRepoInfo).StartIteratingCommits(new FindCustomImplReplacements(resultWriter));
    }

    /**
     * Return list of (repos,branch) pairs to be analyzed
     */
    private static List<RepositoryInfo> ReadListOfRepos(Path path_csv) {
        List<RepositoryInfo> repos = new ArrayList<>();

        try (
                Reader br = Files.newBufferedReader(path_csv);
                CSVReader csvReader = new CSVReaderBuilder(br)
                        .withSkipLines(1)
                        .build()
        ) {

            int rIndex = 0;
            String[] nextRecord;
            while ((nextRecord = csvReader.readNext()) != null) {
                String repoFullName = nextRecord[0];
                String repoBranch = nextRecord[4];
                if(repoBranch==null) {
                    logger.error("Repo with NULL branch: {}", repoFullName);
                    continue;
                }
                RepositoryInfo r = new RepositoryInfo(repoFullName, repoBranch);
                r.SetIndex(rIndex++);
                repos.add(r);
            }
        } catch (IOException | CsvValidationException e) {
            logger.error("Exception while reading list of repos",e);
        }

        return repos;
    }


    private static void ParseArguments(String[] args) {
        Options options = SetupCLIOptions();
        CommandLineParser parser = new DefaultParser(false);
        CommandLine cmdline = null;
        try {
            cmdline = parser.parse(options, args, false);
        } catch (ParseException e) {
            e.printStackTrace();
            help(options, args, 1);
        }

        if(cmdline.hasOption("help"))
            help(options, args,0);

        if(cmdline.hasOption("repos")==false || cmdline.hasOption("clone")==false || cmdline.hasOption("output")==false
                || cmdline.hasOption("prev_output")==false || cmdline.hasOption("apis")==false
                || cmdline.hasOption("threads")==false)
            help(options, args, 1);

        REPO_LIST = Path.of(cmdline.getOptionValue("repos"));
        CLONE_REPOSITORIES_AT = Path.of(cmdline.getOptionValue("clone"));
        RESULT_PATH = AddTimeStamp(cmdline.getOptionValue("output"));
        PREV_RESULT_PATH = Path.of(cmdline.getOptionValue("prev_output"));
        APIS_PATH = Path.of(cmdline.getOptionValue("apis"));

        N_THREADS = Integer.parseInt(cmdline.getOptionValue("threads"));

        DELETE_REPO_AFTER_PROCESS = cmdline.hasOption("delrepos");
        DEBUG_MODE = cmdline.hasOption("debug");


        if(DEBUG_MODE) {
            logger.info("*** DEBUG MODE = ON ***\n\t\t- Repos not deleted\n\t\t- Results flushed immediately");
            DELETE_REPO_AFTER_PROCESS = false;
        }
        else {
            logger.info("*** DEBUG MODE = OFF");
        }

        if(DELETE_REPO_AFTER_PROCESS)
            logger.info("*** Repositories WILL BE DELETED ***");
        else
            logger.info("*** Repositories remain untouched ***");
        logger.info("");
        logger.info("");
    }

    private static Options SetupCLIOptions() {
        Options options = new Options();
        options.addOption( "h", "help",false, "print this message");
        options.addOption("d", "debug", false, "print debugging information");
        options.addOption(null, "delrepos", false, "Delete repository after processing");

        options.addOption("r", "repos", true, "[REQUIRED] Path to list of repos (CSV output from GHS website)");
        options.addOption("c", "clone", true, "[REQUIRED] Path to clone repositories");
        options.addOption("o", "output", true, "[REQUIRED] Path to store SQLite result.");
        options.addOption("p", "prev_output", true, "[REQUIRED] Path to results collected prior to December, to extract interesting commits.");
        options.addOption("a", "apis", true, "[REQUIRED] Path to APIs database.");
        options.addOption("t", "threads", true, "[REQUIRED] Number of parallel repositories to be processed");
        options.getOption("repos").setRequired(true);
        options.getOption("repos").setArgName("path/to/repos.csv");
        options.getOption("clone").setRequired(true);
        options.getOption("clone").setArgName("./repos/");
        options.getOption("output").setRequired(true);
        options.getOption("output").setArgName("./result.sqlite");
        options.getOption("prev_output").setRequired(true);
        options.getOption("prev_output").setArgName("./prev_result.sqlite");
        options.getOption("apis").setRequired(true);
        options.getOption("apis").setArgName("./apis.sqlite");
        options.getOption("threads").setRequired(true);
        options.getOption("threads").setArgName("N");

        return options;
    }

    public static void help(Options options, String[] args, int existCode)
    {
        HelpFormatter formatter = new HelpFormatter();
        formatter.setWidth(200);
        formatter.setOptionComparator(null); // don't change order of Options when displaying help message
        formatter.printHelp( "java -cp XXX.jar XXX.YYY.ZZZ [OPTIONS]", options );
        logger.info("You provided: {}", Arrays.toString(args));
        System.exit(existCode);
    }
}
