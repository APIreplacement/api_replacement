package com.anon;

import com.anon.datatype.*;
import com.anonymous2.Maven.Common.MavenLibInfo;
import com.anonymous2.UtilsPom.PomInfo;
import com.anonymous2.UtilsPom.PomUtilities;
import com.anon.cmdrunners.GitCmdRunner;
import com.anon.cmdrunners.SrcMLCmdRunner;
import com.anonymous2.datatype.*;
import com.anon.helpers.TargetApacheCommonsAPIHelper;
import com.anonymous.parser.parser.ds.MethodDeclarationInfo;
import com.anonymous.parser.parser.ds.MethodInvocationInfo;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.anon.cmdrunners.GitCmdRunner.GetFileContentAtSpecificCommit;

/**
 * How this class works?
 * The `AnalyzeCommit` is called for every commit. We also store current list of methods of project in
 * "projectMethodsAfter" (and "projectMethodsBefore" refers to a similar state for parent commit).
 *
 * We look at changed files and ...
 * 1. If no java file is changed, we just move on
 * 2. Otherwise, we split files to deleted/added (modified becomes both deleted and added). For deleted files, we remove all
 *      methods from "projectMethods". Then, for added files we add all existing methods to "projectMethods".
 */
public class FindCustomImplReplacements implements CommitAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(FindCustomImplReplacements.class);

    static int MAX_CHANGED_FILES = 20; // Note we still analyze huge commits and update database of project methods, but we do not find any replacement candidates from such commits
    static int MAX_DIFF_CHANGES = 1000;
    static int DIFF_CHANGES_WARNING_THRESHOLD = 250;
    final static int SHOW_SRCML_PROCESS_AFTER_N_FILE = 20;

    //Pattern importApacheCommonPattern = Pattern.compile("\\s*import\\s+org\\.apache\\.commons.*");

    ProjectMethods projectMethodsBefore = new ProjectMethods(), projectMethods = new ProjectMethods();
    ResultWriter resultWriter;

    public FindCustomImplReplacements(ResultWriter _resultWriter) {
//        storeResultPath = repo.GetPath().getParent().resolve(repo.GetFullName().split("/")[1] + "_result.csv");
        resultWriter = _resultWriter;
    }

    /**
     * Check if the given commit contains a local method that is replaced with a non-local method and all other invocations of that local method in other files are also removed
     */
    public boolean AnalyzeCommit(RepositoryInfo repo, CommitInfo commit) {

        GitCmdRunner.FilesStatus diffResult = GitCmdRunner.git_diff_GetFilesStatus(repo.GetPath(), commit.parentCommitSHA, commit.commitSHA, "java", true);

        if (diffResult.totalCount == 0) {
            logger.debug("({}/{}) {} Analyzing Commit {}: No Java file changes [DONE]", Main.totalReposProcessed.get(), Main.totalRepos, repo, commit);
            return true;
        }

        // Why code below commented out? Because if we skip analyzing a commit, the method lists in "projectMethods" goes
        //  out of sync with projects, and we'll have wrong list of in-project implemented methods.
//        if (diffResult.totalCount > MAX_CHANGED_FILES) {
//            logger.info("{} {} Analyzing Commit {}: Too Many ({}) Java files changed -> SKIPPING COMMIT", repoLogInfo, repo, commit, diffResult.totalCount);
//            return false;
//        }

        logger.debug("({}/{}) {} Analyzing Commit {}: {} Java files changes...", Main.totalReposProcessed.get(), Main.totalRepos, repo, commit, diffResult.totalCount);

        List<GitCmdRunner.GitFilePath> editedFiles = new ArrayList<>(); // these files are where we look for replacements
        List<Path> deleted = new ArrayList<>(); // methods for such files will be wiped out from ""projectMethods" object
        List<Path> added = new ArrayList<>();   // methods for such files will be added to "projectMethods" object

        deleted.addAll(diffResult.deleted);
        deleted.addAll(diffResult.modified);
        added.addAll(diffResult.added);
        added.addAll(diffResult.modified);
        for (GitCmdRunner.GitFilePath p : diffResult.renamed_from_to) {
            deleted.add(p.oldFilePath);
            added.add(p.filePath);
            editedFiles.add(p);
        }
        for (Path p : diffResult.modified)
            editedFiles.add(new GitCmdRunner.GitFilePath(p));

        // Step 1/2: Update project's local method
        logger.debug("({}/{}) {} Analyzing Commit {}: Updating project methods...", Main.totalReposProcessed.get(), Main.totalRepos, repo, commit);
        UpdateProjectMethods(repo, commit, deleted, added);

        // Step 2/2: Among edited files (=renamed, modified), look for eligible replacements
        if(editedFiles.size()==0)
            logger.debug("({}/{}) {} Analyzing Commit {}: No edited file [DONE]", Main.totalReposProcessed.get(), Main.totalRepos, repo, commit);
        else if(editedFiles.size()>MAX_CHANGED_FILES)
            logger.debug("({}/{}) {} Analyzing Commit {}: Too many edited file ({}) [DONE]", Main.totalReposProcessed.get(), Main.totalRepos, repo, commit, editedFiles.size());
        else {

            // Why lines below commented? We will just check if org.apache.commons is imported and trust developers about how they manage dependencies
            //Set<MavenLibInfo> projectDependencies = null; //ExtractDependencies_onlyApacheCommons(repo, commit);
//            if(projectDependencies==null)
//                logger.info("({}/{}) {} Analyzing Commit {}: No 'pom.xml' or 'build.gradle' files [DONE]", Main.totalReposProcessed.get(), Main.totalRepos, repo, commit);
//            else if(projectDependencies.size()==0)
//                logger.info("({}/{}) {} Analyzing Commit {}: No Apache Commons library among dependencies [DONE]", Main.totalReposProcessed.get(), Main.totalRepos, repo, commit);

            logger.debug("({}/{}) {} Analyzing Commit {}: Looking for candidate replacements in {} edited files...", Main.totalReposProcessed.get(), Main.totalRepos, repo, commit, editedFiles.size());

            List<MethodReplacement> method2APIReplacements_allFiles = new ArrayList<>();

            int nFileAnalyzed = 0;
            for (GitCmdRunner.GitFilePath aEditedFilepath : editedFiles) {
                if ((++nFileAnalyzed) % SHOW_SRCML_PROCESS_AFTER_N_FILE == 0)
                    logger.debug("({}/{}) {} Analyzing Commit {}: Looking for candidate replacements ... ({}/{})", Main.totalReposProcessed.get(), Main.totalRepos, repo, commit, nFileAnalyzed, editedFiles.size());


                // Check which target library (Apache Commons) are imported
                Set<String> importedPackages_onlyApacheCommons = new HashSet<>();
                String content = GetFileContentAtSpecificCommit(repo.GetPath(), commit.commitSHA, aEditedFilepath.filePath);
                if(content!=null) {
                    String[] lines = content.split("\n");
                    List<String> linesArray = Arrays.asList(lines);
                    for(String line: linesArray) {
                        if(line.contains("class ") || line.contains("interface ") )
                            break; // most likely we reached end of import statements
                        if(line.contains("import")==false)
                            continue;
//                        if(line.contains("org.apache.commons"))
//                            debuggingImportPackages = true;
                        for(String aTargetPackage: TargetApacheCommonsAPIHelper.packages)
                            if(line.contains(aTargetPackage)) {
                                importedPackages_onlyApacheCommons.add(aTargetPackage);
                                break;
                            }
//                        Matcher matcher = importApacheCommonPattern.matcher(line);
//                        if(matcher.find()) {
//                            atLeastOneTargetLibraryImportted = true;
//                            break;
//                        }
                    }
                }
                else
                    logger.error("({}/{}) {} Analyzing Commit {}: Failed Fetching file content: {}", Main.totalReposProcessed.get(), Main.totalRepos, repo, commit, aEditedFilepath.filePath);

                if(importedPackages_onlyApacheCommons.isEmpty())
                {
//                    if(debuggingImportPackages)
//                        logger.error("({}/{}) {} Analyzing Commit {}: Skipping a file with no org.apache.commons import ---> BUG BUG BUG ERROR: {}", Main.totalReposProcessed.get(), Main.totalRepos, repo, commit, aEditedFilepath.filePath);
                    continue;
                }

                List<GitCmdRunner.CodeReplacement> codeChanges = GitCmdRunner.git_diff_JustChanges(true, repo.GetPath(), commit.parentCommitSHA, commit.commitSHA, aEditedFilepath.oldFilePath, aEditedFilepath.filePath, true);
                if (codeChanges.size() > MAX_DIFF_CHANGES) {
                    logger.warn("({}/{}) {} Analyzing Commit {}: Skipping a file with too many changes ({}): {}", Main.totalReposProcessed.get(), Main.totalRepos, repo, commit, codeChanges.size(), aEditedFilepath.filePath);
                    continue;
                } else if (codeChanges.size() > DIFF_CHANGES_WARNING_THRESHOLD)
                    logger.warn("({}/{}) {} Analyzing Commit {}: Analyzing a file with many changes ({}): {}", Main.totalReposProcessed.get(), Main.totalRepos, repo, commit, codeChanges.size(), aEditedFilepath.filePath);

                List<MethodReplacement> method2APIReplacements = CheckForCustomImplReplacement(repo, commit, aEditedFilepath, codeChanges);

                if (method2APIReplacements.size() > 0) {
                    for(MethodReplacement m: method2APIReplacements)
                        m.AddPackages(importedPackages_onlyApacheCommons);
                    method2APIReplacements_allFiles.addAll(method2APIReplacements);
                }
            }

            if (method2APIReplacements_allFiles.size() > 0) {
                // Group together similar replacement across different files
                List<MethodReplacementGrouped> method2APIReplacementsGrouped = GroupMethodReplacements(method2APIReplacements_allFiles);

                // Check API is among target APIs (Apache Commons)
                method2APIReplacementsGrouped = AppendCandidateAPIs(method2APIReplacementsGrouped);

                // Add local method body
                AddOldMethodDeclarationInfo(repo, commit, method2APIReplacementsGrouped);

                // Remove replacements where old local method contains the substituted API (=not useful recommendation)
                method2APIReplacementsGrouped = RemoveInstancesWhereAPIAlreadyUsedInLocalMethod(repo, commit, method2APIReplacementsGrouped);

                if (method2APIReplacementsGrouped.size() > 0) {
                    Set<GitCmdRunner.GitFilePath> finalSetOfFiles = new HashSet<>();
                    for(MethodReplacementGrouped m: method2APIReplacementsGrouped)
                    {
//                        m.hasFoundAnyDependencyFile = projectDependencies!=null;
//                        m.nDependencies = projectDependencies.size();
                        finalSetOfFiles.addAll(m.filesAndLineNumbers.keySet());
                    }


                    List<ImportStatementChanges> importChanges = new ArrayList<>();
                    for(GitCmdRunner.GitFilePath p: finalSetOfFiles){
                        ImportStatementChanges importStatementChanges = GitCmdRunner.git_diff_JustImportsChanges(repo.GetPath(), commit.parentCommitSHA, commit.commitSHA, p.oldFilePath, p.filePath, true);
                        importChanges.add(importStatementChanges);
                    }


                    // Write Results for this commit
                    resultWriter.WriteResult_Method2API(repo, commit, method2APIReplacementsGrouped);
                    resultWriter.WriteResult_Imports(repo, commit, importChanges);

                    if (Main.DEBUG_MODE)
                        resultWriter.FlushResults(repo);
                    logger.debug("({}/{}) {} Analyzing Commit {}: Unique replacements found: {} [DONE]", Main.totalReposProcessed.get(), Main.totalRepos, repo, commit, method2APIReplacementsGrouped.size());
                }
            } else
                logger.debug("({}/{}) {} Analyzing Commit {}: Unique replacements found: None [DONE]", Main.totalReposProcessed.get(), Main.totalRepos, repo, commit);
        }

        projectMethodsBefore = projectMethods;
        return true;
    }

    /**
     * Update list of dependencies of repository
     * @return return non-null if at least one pom.xml or build.gradle or build.gradle.kts found.
     */
    private Set<MavenLibInfo> ExtractDependencies_onlyApacheCommons(RepositoryInfo repo, CommitInfo commit) {
        Set<MavenLibInfo> dependencies = new HashSet<>();
        List<String> pomFiles = GitCmdRunner.ListFilesAtSpecificCommit(repo.GetPath(), commit.commitSHA, "pom.xml");
        if(pomFiles.size()>0) {
            for (String aPomPath : pomFiles) {
                Path tempPath = GitCmdRunner.GetFileContentAtSpecificCommitAndSaveOnTempFile(repo.GetPath(), commit.commitSHA, Path.of(aPomPath));
                PomInfo myPomInfo = new PomInfo(repo.GetFullName(), aPomPath, tempPath.toString());
                PomUtilities.RESULTS res = PomUtilities.ExtractDependencies(myPomInfo, null);
                for (MavenLibInfo dep : myPomInfo.GetDependencies())
                    if (dep.groupId.equals("org.apache.commons") || dep.groupId.equals("commons-io") || dep.groupId.equals("commons-daemon"))
                        dependencies.add(dep);
            }
            return dependencies;
        }
        else
        {
            List<String> allFiles = GitCmdRunner.ListFilesAtSpecificCommit(repo.GetPath(), commit.commitSHA, null);
            List<String> buildGradleFiles = new ArrayList<>();
            for (String aFile: allFiles)
                if(aFile.endsWith("build.gradle") || aFile.endsWith("build.gradle.kts"))
                    buildGradleFiles.add(aFile);

            if(buildGradleFiles.size()>0)
            {
                for (String aBuildGradle : buildGradleFiles) {
                    Path tempPath = GitCmdRunner.GetFileContentAtSpecificCommitAndSaveOnTempFile(repo.GetPath(), commit.commitSHA, Path.of(aBuildGradle));
                    List<MavenLibInfo> extractedDependencies = ExtractDependenciesFromBuildGradleFile(tempPath, aBuildGradle);

                    for (MavenLibInfo dep : extractedDependencies)
                        if (dep.groupId.equals("org.apache.commons") || dep.groupId.equals("commons-io") || dep.groupId.equals("commons-daemon"))
                            dependencies.add(dep);
                }
                return dependencies;
            }
            else
            {
                return null;
            }
        }
    }

    private List<MavenLibInfo> ExtractDependenciesFromBuildGradleFile(Path filepath, String originalPath)
    {
        List<MavenLibInfo> res = new ArrayList<>();

        String aFieldQuoted="\\s*\"([.\\w-]+)\"\\s*";
        String pattern1_str = String.format("\\w+\\(%s,%s,%s\\)", aFieldQuoted, aFieldQuoted, aFieldQuoted);
        Pattern pattern1 = Pattern.compile(pattern1_str);
        Pattern pattern2 = Pattern.compile("([.\\w-]+):([.\\w-]+):([.\\w-]+)");

        BufferedReader br  = null;
        try {
            br = new BufferedReader(new FileReader(String.valueOf(filepath)));
            String st;
            int nestedLevel=0;
            boolean dependencyBlockReached=false;
            while ((st = br.readLine()) != null)
            {
                if(st.contains("dependencies {") && dependencyBlockReached==false) {
                    dependencyBlockReached = true;
                    nestedLevel=0;
                }
                else if(dependencyBlockReached) {
                    boolean anyMatchWithPattern1 = false;
                    Matcher m1 = pattern1.matcher(st);
                    while (m1.find()) {
                        anyMatchWithPattern1 = true;
                        String groupId = m1.group(1);
                        String artifactId = m1.group(2);
                        String version = m1.group(3);
                        res.add(new MavenLibInfo(groupId, artifactId));
                    }
                    if(anyMatchWithPattern1==false)
                    {
                        Matcher m2 = pattern2.matcher(st);
                        while (m2.find()) {
                            String groupId = m2.group(1);
                            String artifactId = m2.group(2);
                            String version = m2.group(3);
                            res.add(new MavenLibInfo(groupId, artifactId));
                        }
                    }

                    if(st.contains("{"))
                        nestedLevel++;
                    else if(st.contains("}"))
                    {
                        nestedLevel--;
                        if(nestedLevel<0)
                            dependencyBlockReached=false;
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed parsing Groovy build.gradle at {}", originalPath, e);
        }

        return res;
    }

    /**
     * Given a list of method2APIReplacementsGrouped, only keep those coming from a library on which the repository depends
     */
    public List<MethodReplacementGrouped> AppendCandidateAPIs(List<MethodReplacementGrouped> method2APIReplacementsGrouped) {
//        List<MethodReplacementGrouped> method2APIReplacementsGrouped_filtered = new ArrayList<>();
        for(MethodReplacementGrouped aMethodReplacementGroup: method2APIReplacementsGrouped)
        {
            List<MethodDeclarationInfo> candidateAPIs = TargetApacheCommonsAPIHelper.FindMatchingAPIs(aMethodReplacementGroup.rep.newMethod, aMethodReplacementGroup.rep.importedPackages);
//            candidateAPIs = RemoveAPIsThatLibraryIsNotAmongDependencies(candidateAPIs, projectDependencies);
            aMethodReplacementGroup.AddCandidateAPis(candidateAPIs);
//            method2APIReplacementsGrouped_filtered.add(aMethodReplacementGroup);
        }
//        return method2APIReplacementsGrouped_filtered;
        return method2APIReplacementsGrouped;
    }

    /**
     * Given a set of APIs (from different Apache libraries), only returns those which belongs to a library which is
     * among the repository's dependencies.
     */
    static private List<MethodDeclarationInfo> RemoveAPIsThatLibraryIsNotAmongDependencies(List<MethodDeclarationInfo> matchingAPIs, Set<MavenLibInfo> dependencies) {
        List<MethodDeclarationInfo> res = new ArrayList<>();
        for(MethodDeclarationInfo m: matchingAPIs)
        {
            String[] lib_project = m.projectName.split("\\|");
            for(MavenLibInfo aDependency: dependencies) {
                if (aDependency.groupId.equals(lib_project[0]) && aDependency.artifactId.equals(lib_project[1])) {
                    // Well, this matching APIs belongs to a library which is among the repository's dependencies
                    res.add(m);
                    break;
                }
            }
        }
        return res;
    }

    private List<ImportStatementChanges> RemoveUnnecessaryImports(List<ImportStatementChanges> importChanges_allFiles, List<MethodReplacementGrouped> method2APIReplacementsGrouped) {
        if(method2APIReplacementsGrouped.size()==0)
            return new ArrayList<>();

        Set<Path> keyFiles = new HashSet<>();
        for(MethodReplacementGrouped aMethodReplacement_group: method2APIReplacementsGrouped)
            keyFiles.add(aMethodReplacement_group.rep.filePath.filePath);

        List<ImportStatementChanges> res = new ArrayList<>();
        for(ImportStatementChanges aImportChange: importChanges_allFiles)
            if(keyFiles.contains(aImportChange.filePath))
                res.add(aImportChange);
        return res;
    }

    /**
     * Given list of method replacements (grouped), remove instances where for replacement Method->API, the Method is
     * already using API. This means that developer is already aware of API and such replacement is not useful for her.
     */
    private List<MethodReplacementGrouped> RemoveInstancesWhereAPIAlreadyUsedInLocalMethod(RepositoryInfo repo, CommitInfo commit, List<MethodReplacementGrouped> method2APIReplacementsGrouped) {
        List<MethodReplacementGrouped> res = new ArrayList<>();
        for(MethodReplacementGrouped aMethodReplacement_group: method2APIReplacementsGrouped)
        {
            if(aMethodReplacement_group.oldMethodDeclInfo == null || aMethodReplacement_group.oldMethodDeclInfo.declarationCode_generated==null) {
                logger.error("({}/{}) {} Analyzing Commit {}: Replacement ignored since local method is null (rep: {})", Main.totalReposProcessed.get(), Main.totalRepos, repo, commit, aMethodReplacement_group.rep);
                continue;
            }

            String localMethodBody = aMethodReplacement_group.oldMethodDeclInfo.declarationCode_generated;
            int braceIndex = localMethodBody.indexOf('{');

            // If we just found method declaration (without body), we assume it's fine, and we consider it without
            // checking if newAPI was already used in the local method's body.
            if(braceIndex!=-1)
            {
                localMethodBody = localMethodBody.substring(braceIndex);
                if (localMethodBody.contains(aMethodReplacement_group.rep.newMethod.name))
                    continue;
            }

            res.add(aMethodReplacement_group);
        }
        return res;
    }

    /**
     * Update ".oldMethodDeclInfo" field of given MethodReplacementGrouped objects.
     */
    private void AddOldMethodDeclarationInfo(RepositoryInfo repo, CommitInfo commit, List<MethodReplacementGrouped> method2APIReplacementsGrouped) {
        for(MethodReplacementGrouped aMethodReplacement_group: method2APIReplacementsGrouped)
        {
            MethodDeclarationInfo oldMethodDecl = GetMethodDeclarationInfo(aMethodReplacement_group.rep.oldMethod, this.projectMethodsBefore);

            if(oldMethodDecl == null)
            {
                logger.error("({}/{}) {} Analyzing Commit {}: {} BUG !!!!!!!!!!!!!!!!!!", Main.totalReposProcessed.get(), Main.totalRepos, repo, commit, aMethodReplacement_group.rep.oldMethod.name);
                logger.error("LOGICAL BUG\nLOGICAL BUG\nLOGICAL BUG\nLOGICAL BUG");
                logger.error("LOGICAL BUG\nLOGICAL BUG\nLOGICAL BUG\nLOGICAL BUG");
                logger.error("LOGICAL BUG\nLOGICAL BUG\nLOGICAL BUG\nLOGICAL BUG");
                logger.error("LOGICAL BUG\nLOGICAL BUG\nLOGICAL BUG\nLOGICAL BUG");
                continue;
            }

            if(oldMethodDecl.isConstructor) // we don't have body information since we added such methods using `AddDefaultConstructors()`
            {
                oldMethodDecl.declarationCode_generated = "(((CONSTRUCTOR)))"; //TODO
            }
            else
            {
                String content = GetFileContentAtSpecificCommit(repo.GetPath(), commit.parentCommitSHA, Path.of(oldMethodDecl.fileRelativePath));
                if(content!=null) {
                    String[] lines = content.split("\n");
                    List<String> linesArray = Arrays.asList(lines);

                    if(linesArray.size() <= oldMethodDecl.lineEnd) {
                        logger.warn("({}/{}) {} Analyzing Commit {}: End of local methods ({}) after end of file ({})!! {}", Main.totalReposProcessed.get(), Main.totalRepos, repo, commit, oldMethodDecl.lineEnd, linesArray.size(), aMethodReplacement_group.rep.oldMethod);
                        oldMethodDecl.declarationCode_generated = null;
                        continue;
                    }
                    List<String> sub = linesArray.subList(oldMethodDecl.lineStart - 1, oldMethodDecl.lineEnd);
                    oldMethodDecl.declarationCode_generated = String.join("\n", sub);
                }
                else {
                    oldMethodDecl.declarationCode_generated = null;
                    logger.error("({}/{}) {} Analyzing Commit {}: Failed to get the body of a local method: {}", Main.totalReposProcessed.get(), Main.totalRepos, repo, commit, aMethodReplacement_group.rep.oldMethod);
                }
            }

            aMethodReplacement_group.oldMethodDeclInfo = oldMethodDecl;

        }
    }

    private List<MethodReplacementGrouped> GroupMethodReplacements(List<MethodReplacement> method2APIReplacements) {
        //Map<MethodReplacement, Long> methodToAPIReplacementsCounted = methodReplacements.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        Map<MethodReplacement, MethodReplacementGrouped> res = new HashMap<>();
        for(MethodReplacement rep: method2APIReplacements)
        {
            MethodReplacementGrouped mrg;
            if(res.containsKey(rep)){
                mrg = res.get(rep);
            } else {
                mrg = new MethodReplacementGrouped(rep);
                res.put(rep, mrg);
            }

            // we could also use "rep.oldMethod.filePath" but then we would store (for renamed files) the old path
            mrg.AddNewCase(rep.filePath, rep.line_before, rep.line_after);
        }

        ArrayList<MethodReplacementGrouped> resList = new ArrayList<>(res.values());
        return resList;
    }

    /**
     * Store results on a file
     */
    public void Conclude(RepositoryInfo repo) {
        logger.debug("({}/{}) {} Analyzing All Commit Finished. Writing final result ...", Main.totalReposProcessed.get(), Main.totalRepos, repo);
        resultWriter.FlushResults(repo);
        logger.info("({}/{}) {} Analyzing All Commit Finished. Writing final result DONE", Main.totalReposProcessed.get(), Main.totalRepos, repo);
    }

    /**
     * Return a list of MethodReplacements that meet our criteria (i.e., old one is local and removed from all other files, and new one is non-local library API).
     */
    public List<MethodReplacement> CheckForCustomImplReplacement(RepositoryInfo repo, CommitInfo commit, GitCmdRunner.GitFilePath path, List<GitCmdRunner.CodeReplacement> codeChanges) {

        List<MethodReplacement> method2APIReplacements = new ArrayList<>();

        for(GitCmdRunner.CodeReplacement aReplace: codeChanges)
        {
            if(aReplace.removedCode==null || aReplace.addedCode==null)
                continue;

            List<MethodInvocationInfo> removedCalls = SrcMLCmdRunner.ExtractMethodsCallsFromText(aReplace.removedCode, "Java", path.oldFilePath);
            if (removedCalls.size() == 0)  continue;
            List<MethodInvocationInfo> addedCalls = SrcMLCmdRunner.ExtractMethodsCallsFromText(aReplace.addedCode, "Java", path.filePath);
            if (addedCalls.size() == 0) continue;

            if(removedCalls.equals(addedCalls))
                continue;

            boolean found = false;
            MethodInvocationInfo oldMethod = null;
            MethodInvocationInfo newMethodAPI = null;

            Set<MethodInvocationInfo> commonMethods = FindCommonMethods(removedCalls, addedCalls);

            boolean onlyRemovedAdded = onlyMethodsAreAddedOrOnlyRemoved(removedCalls, addedCalls, commonMethods);
            if(onlyRemovedAdded)
                continue;


            // aReplace collected from git diff could be like "X().Y().foo().Z()"->"X().Y().bar().Z()"
            // with code below we look for the first change among method calls
            // NOTE:It means for the following change "local.X()" -> "library.X()" we don't consider X->X a
            //      replacement. while in above example it's a false negative, this algorithm saves us tons of
            //      false positive where a non-method-call part is changed, like: "a.X()"->"b.X()"
            int removedMethodsIdx = 0, addedMethodsIdx=0;
            int removedMethodsCharIdx = 0, addedMethodsCharIdx=0;
            while(removedMethodsIdx<removedCalls.size() && addedMethodsIdx<addedCalls.size())
            {
                oldMethod = removedCalls.get(removedMethodsIdx);
                newMethodAPI = addedCalls.get(addedMethodsIdx);

                if(oldMethod.equals(newMethodAPI)) {
                    removedMethodsCharIdx = (aReplace.removedCode.indexOf(oldMethod.name, removedMethodsCharIdx)+oldMethod.name.length());
                    removedMethodsIdx++;

                    addedMethodsCharIdx = (aReplace.addedCode.indexOf(newMethodAPI.name, addedMethodsCharIdx)+newMethodAPI.name.length());
                    addedMethodsIdx++;
                    continue;
                }

                if(commonMethods.contains(oldMethod)) {
                    removedMethodsCharIdx = (aReplace.removedCode.indexOf(oldMethod.name, removedMethodsCharIdx)+oldMethod.name.length());
                    removedMethodsIdx++;
                    continue;
                }
                if(commonMethods.contains(newMethodAPI)) {
                    addedMethodsCharIdx = (aReplace.addedCode.indexOf(newMethodAPI.name, addedMethodsCharIdx)+newMethodAPI.name.length());
                    addedMethodsIdx++;
                    continue;
                }



                // Checking left-hand side of added/removed method/API is not identical
                int oldMethodCharIndex = aReplace.removedCode.indexOf(oldMethod.name+"(", removedMethodsCharIdx); // Why we add +"("? consider "logger.log(..)". If we look for "log", instead of "logger." left-side, we get empty "".
                if(oldMethodCharIndex == -1)
                    oldMethodCharIndex = aReplace.removedCode.indexOf(oldMethod.name+"<", removedMethodsCharIdx);
                if(oldMethodCharIndex == -1)
                    oldMethodCharIndex = aReplace.removedCode.indexOf(oldMethod.name, removedMethodsCharIdx);

                int newMethodAPICharIndex = aReplace.addedCode.indexOf(newMethodAPI.name+"(", addedMethodsCharIdx);
                if (newMethodAPICharIndex==-1)
                    newMethodAPICharIndex = aReplace.addedCode.indexOf(newMethodAPI.name+"<", addedMethodsCharIdx);
                if (newMethodAPICharIndex==-1)
                    newMethodAPICharIndex = aReplace.addedCode.indexOf(newMethodAPI.name, addedMethodsCharIdx);

                if(oldMethodCharIndex==-1 || newMethodAPICharIndex==-1)
                    break; // this might happen when method name is not ASCII. See https://github.com/ahmetaa/zemberek-nlp/commit/6ceabdcfab135839f4472fdba251f2053cbb4d6e#diff-0cf919d9243178774edfc5f7887acd9278003410161828bcb1f03373f8bfa477R157
                String leftSideOfRemovedMethod = aReplace.removedCode.substring(0, oldMethodCharIndex);
                String leftSideOfAddedAPI = aReplace.addedCode.substring(0, newMethodAPICharIndex);
                if(leftSideOfRemovedMethod.equals(leftSideOfAddedAPI) && leftSideOfRemovedMethod.trim().isEmpty()==false
                && leftSideOfRemovedMethod.trim().equals(".")==false)
                {
                    removedMethodsCharIdx = (aReplace.removedCode.indexOf(oldMethod.name, removedMethodsCharIdx)+oldMethod.name.length());
                    removedMethodsIdx++;

                    addedMethodsCharIdx = (aReplace.addedCode.indexOf(newMethodAPI.name, addedMethodsCharIdx)+newMethodAPI.name.length());
                    addedMethodsIdx++;
                    continue;
                }



                found = true;
                break;
            }


            if(!found)
                continue;

            if(IsNewMethodTrulyAnAPI(newMethodAPI)==false)
                continue;

            boolean WasOldMethodLocal = IsMethodInvocationLocal(oldMethod, this.projectMethodsBefore.methodsDeclarationsInEachFile);
            boolean DoesOldMethodExistAfter = IsMethodInvocationLocal(oldMethod, this.projectMethods.methodsDeclarationsInEachFile);
            boolean isNewMethodLocal = IsMethodInvocationLocal(newMethodAPI, this.projectMethods.methodsDeclarationsInEachFile);
            boolean isNewMethodCalled = IsMethodCalled(newMethodAPI, this.projectMethods.methodsCallsInEachFile); // Why "isNewMethodCalled"? sometimes we found a API call in comments

            if (WasOldMethodLocal==false || DoesOldMethodExistAfter==true || isNewMethodLocal==true || isNewMethodCalled==false)
                continue;


            String addedMethods = ConvertMethodListToString(addedCalls);
            String removedMethods = ConvertMethodListToString(removedCalls);
            method2APIReplacements.add(new MethodReplacement(oldMethod, newMethodAPI, path, aReplace.lineStart_before, aReplace.lineStart_after, aReplace.removedCode, aReplace.addedCode, removedCalls.size(), addedCalls.size(), removedMethods, addedMethods));
            //System.out.printf("*********** Found a pair: %s - %s \n", oldMethod.methodName, newMethodAPI.methodName);
        }

        return method2APIReplacements;
    }

    private String ConvertMethodListToString(List<MethodInvocationInfo> methods)
    {
        StringBuilder res= new StringBuilder();
        for(int i=0; i<methods.size();i++) {
            res.append(methods.get(i).toString());
            if(i!=methods.size()-1)
                res.append(",");
        }
        return res.toString();
    }

    private boolean onlyMethodsAreAddedOrOnlyRemoved(List<MethodInvocationInfo> removedCalls, List<MethodInvocationInfo> addedCalls, Set<MethodInvocationInfo> commonMethods) {
        boolean allMethodExistInCommonMethods;

        allMethodExistInCommonMethods = true;
        for(MethodInvocationInfo m: removedCalls)
            if(commonMethods.contains(m) == false)
            {
                allMethodExistInCommonMethods = false;
                break;
            }
        if(allMethodExistInCommonMethods)
            return true;

        allMethodExistInCommonMethods = true;
        for(MethodInvocationInfo m: addedCalls)
            if(commonMethods.contains(m) == false)
            {
                allMethodExistInCommonMethods = false;
                break;
            }
        if(allMethodExistInCommonMethods)
            return true;

        return false;
    }

    private boolean IsNewMethodTrulyAnAPI(MethodInvocationInfo newMethodAPI)
    {
        if(     newMethodAPI.name.equals("super") ||
                newMethodAPI.name.equals("this") ||
                newMethodAPI.name.startsWith("assert") )
            return false;
        return true;
    }

    private Set<MethodInvocationInfo> FindCommonMethods(List<MethodInvocationInfo> removedCalls, List<MethodInvocationInfo> addedCalls)
    {
        Set<MethodInvocationInfo> common = new HashSet<>(removedCalls);
        common.retainAll(addedCalls);
        return common;
    }


    private void UpdateProjectMethods(RepositoryInfo repo, CommitInfo commit, List<Path> deleted, List<Path> added) {
        int nExtractedMethodDeclarations = 0, nExtractedMethodCalls = 0;

        ProjectMethods projectMethods_new = new ProjectMethods(this.projectMethodsBefore);

        logger.debug("({}/{}) {} Analyzing Commit {}: Cleaning method calls/decls from deleted files... ", Main.totalReposProcessed.get(), Main.totalRepos, repo, commit);
        for (Path p : deleted) {
            projectMethods_new.methodsDeclarationsInEachFile.remove(p);
            projectMethods_new.methodsCallsInEachFile.remove(p);
        }

        int nFileAnalyzed = 0;
        for (Path addedFilePath : added) {
            nFileAnalyzed++;
            if (nFileAnalyzed % SHOW_SRCML_PROCESS_AFTER_N_FILE == 0)
                logger.debug("({}/{}) {} Analyzing Commit {}: Extracting method calls/decls from new/modified files... ({}/{})", Main.totalReposProcessed.get(), Main.totalRepos, repo, commit, nFileAnalyzed, added.size());

//            if(addedFilePath.toString().equals("core/java/android/view/ViewTreeObserver.java")==false)
//                continue;

            Path tmp_addedFilePath = GitCmdRunner.GetFileContentAtSpecificCommitAndSaveOnTempFile(repo.GetPath(), commit.commitSHA, addedFilePath);
            if(tmp_addedFilePath == null)
            {
                logger.error("({}/{}) {} Analyzing Commit {}: Failed to get content of a newly added file... ({}/{}): {}", Main.totalReposProcessed.get(), Main.totalRepos, repo, commit, nFileAnalyzed, added.size(), addedFilePath);
                continue;
            }

            List<MethodDeclarationInfo> methodDecls = SrcMLCmdRunner.ExtractMethodsDeclarations(tmp_addedFilePath, "Java", addedFilePath, false);
            List<SrcMLCmdRunner.ClassInfo> classes = SrcMLCmdRunner.ExtractClassDeclarations(tmp_addedFilePath, "Java", addedFilePath);
            AddDefaultConstructors(methodDecls, classes);
            HashSet<MethodDeclarationInfo> methodDeclarations_set = new HashSet<>(methodDecls);
            projectMethods_new.methodsDeclarationsInEachFile.put(addedFilePath, methodDeclarations_set);
            nExtractedMethodDeclarations += methodDeclarations_set.size();

            List<MethodInvocationInfo> methodCalls = SrcMLCmdRunner.ExtractMethodsCalls(tmp_addedFilePath, "Java", addedFilePath);
            HashSet<MethodInvocationInfo> methodCalls_set = new HashSet<>(methodCalls);
            projectMethods_new.methodsCallsInEachFile.put(addedFilePath, methodCalls_set);
            nExtractedMethodCalls += methodCalls_set.size();

            tmp_addedFilePath.toFile().delete();
        }

        int nTotalMethodDecls = projectMethods_new.CountTotalMethodDeclarations();
        int nTotalMethodCalls = projectMethods_new.CountTotalMethodCalls();
        logger.debug("({}/{}) {} Analyzing Commit {}: Extracting method calls/decls from {} new/modified files: {} new methods decl  {} new method calls | TOTAL: {} methods decl  {} method calls",
                Main.totalReposProcessed.get(), Main.totalRepos, repo, commit, added.size(), nExtractedMethodDeclarations, nExtractedMethodCalls,
                nTotalMethodDecls, nTotalMethodCalls);

        this.projectMethods = projectMethods_new;
    }

    private void AddDefaultConstructors(List<MethodDeclarationInfo> methodDecls, List<SrcMLCmdRunner.ClassInfo> classes) {
        for(SrcMLCmdRunner.ClassInfo aClass: classes)
        {
            boolean found=false;
            for(MethodDeclarationInfo mi: methodDecls)
                if(mi.nArgs==0 && aClass.className.equals(mi.name))
                {
                    found = true;
                    break;
                }
            if(found==false) {
                MethodDeclarationInfo newMD = new MethodDeclarationInfo(null, null, aClass.className, 0, null, aClass.lineStart, aClass.lineEnd, -1);
                newMD.fileRelativePath = String.valueOf(aClass.filePath);
                newMD.isConstructor = true;
                methodDecls.add(newMD);
            }
        }
    }

    /**
     * Check if given method is among declared/implemented methods of the project (therefore, it is not an API from a library)
     * @return true if such method is found, otherwise false.
     */
    private boolean IsMethodInvocationLocal(MethodInvocationInfo methodInfo, Map<Path, Set<MethodDeclarationInfo>> projectMethodsDeclarations) {

        for (Map.Entry<Path, Set<MethodDeclarationInfo>> entry : projectMethodsDeclarations.entrySet()) {
            Set<MethodDeclarationInfo> methodDeclarations = entry.getValue();
            for(MethodDeclarationInfo aDec: methodDeclarations)
                if(aDec.MatchMethodInvocation(methodInfo))
                    return true;
        }

        return false;
    }

    /**
     * Check if given method is among declared/implemented methods of the project (therefore, it is not an API from a library)
     * @return returns if such matching method declaration is found, otherwise null.
     */
    private MethodDeclarationInfo GetMethodDeclarationInfo(MethodInvocationInfo methodInfo, ProjectMethods projectMethods) {
        for (Map.Entry<Path, Set<MethodDeclarationInfo>> entry : projectMethods.methodsDeclarationsInEachFile.entrySet()) {
            Set<MethodDeclarationInfo> methodDeclarations = entry.getValue();
            for (MethodDeclarationInfo m : methodDeclarations)
                if (m.MatchMethodInvocation(methodInfo))
                    return m;
        }

        return null;
    }

    /**
     * Check if given method is among method invocations of the project (therefore, it is not an API from a library)
     */
    private boolean IsMethodCalled(MethodInvocationInfo methodInfo, Map<Path, Set<MethodInvocationInfo>> projectMethodsCalls) {
        for (Map.Entry<Path, Set<MethodInvocationInfo>> entry : projectMethodsCalls.entrySet()) {
            Set<MethodInvocationInfo> methodCalls = entry.getValue();
            if(methodCalls.contains(methodInfo))
                return true;
        }

        return false;
    }

    /**
     * The purpose of this class is to accomodate several method2APIReplacements with same oldMethod and same newAPI
     * across different files into one object to be stored
     */
    static class MethodReplacementGrouped {
        MethodReplacement rep;
        MethodDeclarationInfo oldMethodDeclInfo;
        int count;
        HashMap<GitCmdRunner.GitFilePath, List<ImmutablePair<Integer, Integer>>> filesAndLineNumbers;
        List<Integer> candidateAPIIDs = new ArrayList<>();
//        boolean hasFoundAnyDependencyFile = false;
//        int nDependencies = 0;



        public MethodReplacementGrouped(MethodReplacement rep) {
            this.rep = rep;
            count = 0;
            filesAndLineNumbers = new HashMap<>();
        }

        public void AddNewCase(GitCmdRunner.GitFilePath _newFile, int lineNumber_before, int lineNumber_after)
        {
            if(filesAndLineNumbers.containsKey(_newFile))
            {
                filesAndLineNumbers.get(_newFile).add(new ImmutablePair<Integer, Integer>(lineNumber_before, lineNumber_after));
            }
            else
            {
                ArrayList<ImmutablePair<Integer, Integer>> l = new ArrayList<>();
                l.add(new ImmutablePair<Integer, Integer>(lineNumber_before, lineNumber_after));
                filesAndLineNumbers.put(_newFile, l);
            }
            count++;
        }

        public void AddCandidateAPis(List<MethodDeclarationInfo> candidateAPIs) {
            for(MethodDeclarationInfo m: candidateAPIs)
                candidateAPIIDs.add(m.databaseId);
        }
    }

}
