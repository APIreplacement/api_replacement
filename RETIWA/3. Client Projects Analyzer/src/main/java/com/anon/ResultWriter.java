package com.anon;

import com.anon.datatype.CommitInfo;
import com.anon.datatype.ImportStatementChanges;
import com.anon.datatype.MethodReplacement;
import com.anon.datatype.RepositoryInfo;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.*;
import java.util.List;
import java.util.stream.Collectors;

public class ResultWriter {
    private static final Logger logger = LoggerFactory.getLogger(ResultWriter.class);

    final static int STORE_RESULT_AFTER_EVERY_N_REPLACEMENTS = 20; // we might find several cases within one commit

    PreparedStatement stm_methods;
    PreparedStatement stm_imports;
    int nResultBuffered;

    public ResultWriter(PreparedStatement _stm_methods, PreparedStatement _stm_imports) {
        this.stm_methods = _stm_methods;
        this.stm_imports = _stm_imports;
        this.nResultBuffered = 0;
    }

    /**
     * This method create batches of results, and write them on database file after every STORE_RESULT_AFTER_EVERY_N_REPLACEMENTS.
     * So make sure to run `FlushResults()` to write buffered results eventually.
     */
    public void WriteResult_Method2API(RepositoryInfo repo, CommitInfo commit, List<FindCustomImplReplacements.MethodReplacementGrouped> method2APIReplacementsGrouped)
    {
        if(method2APIReplacementsGrouped ==null || method2APIReplacementsGrouped.size()==0)
            return;

        for (FindCustomImplReplacements.MethodReplacementGrouped entry : method2APIReplacementsGrouped) {
            MethodReplacement mr = entry.rep;
            int count = entry.count;

            boolean firstItem=true;
            StringBuilder occurances_strBuilder = new StringBuilder();
            for (var aOccurance : entry.filesAndLineNumbers.entrySet()) {
                if(firstItem==false)
                    occurances_strBuilder.append(", ");
                else
                    firstItem=false;
                occurances_strBuilder.append(String.format("(\"%s\"->\"%s\"|", aOccurance.getKey().oldFilePath, aOccurance.getKey().filePath));
                for(int i=0; i<aOccurance.getValue().size(); i++)
                {
                    ImmutablePair<Integer, Integer> lineNumberPair = aOccurance.getValue().get(i);
                    occurances_strBuilder.append(String.format("[%d,%d]", lineNumberPair.getKey(), lineNumberPair.getValue()));
                }
                occurances_strBuilder.append(")");
            }

            String candidateAPIIDs_csv;
            if(entry.candidateAPIIDs.size()<20)
                candidateAPIIDs_csv = entry.candidateAPIIDs.stream().map(String::valueOf).collect(Collectors.joining(","));
            else
                candidateAPIIDs_csv = entry.candidateAPIIDs.subList(0,20).stream().map(String::valueOf).collect(Collectors.joining(","));

            try {
                stm_methods.setString(2,repo.GetFullName());
                stm_methods.setString(3, repo.GetDefaultBranch());
                stm_methods.setString(4, commit.parentCommitSHA);
                stm_methods.setString(5, commit.commitSHA);
                stm_methods.setString(6, commit.commitMessage_subject);
                stm_methods.setString(7, mr.oldMethod.toString());
                stm_methods.setString(8, mr.newMethod.toString());
                stm_methods.setString(9,  String.valueOf(count));
                stm_methods.setString(10, occurances_strBuilder.toString());
                stm_methods.setString(11,  String.valueOf(entry.oldMethodDeclInfo.fileRelativePath));
                stm_methods.setString(12,  String.valueOf(entry.oldMethodDeclInfo.lineStart));
                stm_methods.setString(13,  String.valueOf(entry.oldMethodDeclInfo.lineEnd));
                stm_methods.setString(14,  entry.oldMethodDeclInfo.declarationCode_generated);
                stm_methods.setString(15,  mr.removedCode);
                stm_methods.setString(16,  mr.addedCode);
                stm_methods.setString(17,  mr.removedMethods);
                stm_methods.setString(18,  mr.addedMethods);
                stm_methods.setInt(19,  mr.nRemovedMethods);
                stm_methods.setInt(20,  mr.nAddedMethods);
                stm_methods.setInt(21,  entry.candidateAPIIDs.size());
                stm_methods.setString(22, candidateAPIIDs_csv);
//                stm_methods.setBoolean(23,  entry.hasFoundAnyDependencyFile);
//                stm_methods.setInt(24,  entry.nDependencies);


                stm_methods.addBatch();
                nResultBuffered++;

            } catch (SQLException e) {
                logger.error("({}/{}) {}: Writing a result FAILED", Main.totalReposProcessed.get(), Main.totalRepos, repo, e);
            }
        }


        if(nResultBuffered > STORE_RESULT_AFTER_EVERY_N_REPLACEMENTS) {
            FlushResults(repo);
        }
        else
            logger.debug("({}/{}) {}: Writing {} buffered intermediate results skipped", Main.totalReposProcessed.get(), Main.totalRepos, repo, nResultBuffered);
    }


    public void WriteResult_Imports(RepositoryInfo repo, CommitInfo commit, List<ImportStatementChanges> importChanges_allFiles)
    {
        for (ImportStatementChanges entry : importChanges_allFiles) {
            try {
                stm_imports.setString(2,repo.GetFullName());
                stm_imports.setString(3, repo.GetDefaultBranch());
                stm_imports.setString(4, commit.parentCommitSHA);
                stm_imports.setString(5, commit.commitSHA);
                stm_imports.setString(6, String.valueOf(entry.filePath));
                stm_imports.setString(7, entry.GetAddedImports());
                stm_imports.setString(8, entry.GetRemovedImports());
                stm_imports.addBatch();

            } catch (SQLException e) {
                logger.error("({}/{}) {}: Writing a (import) result FAILED", Main.totalReposProcessed.get(), Main.totalRepos, repo, e);
            }
        }
    }


    public void FlushResults(RepositoryInfo repo)
    {
        try {
            stm_methods.executeBatch();
            stm_imports.executeBatch();
            logger.debug("({}/{}) {}: Writing a batch of {} intermediate results successful", Main.totalReposProcessed.get(), Main.totalRepos, repo, nResultBuffered);
            nResultBuffered = 0;
        } catch (SQLException e) {
            logger.error("({}/{}) {}: Writing a batch of {} intermediate results FAILED", Main.totalReposProcessed.get(), Main.totalRepos, repo, nResultBuffered, e);
        }
    }

    public static PreparedStatement GetNewDBPreparedStatement_Methods(Connection conn) throws SQLException {
        String q_insert = "INSERT INTO APIReplacements VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        PreparedStatement prp_stm = conn.prepareStatement(q_insert);
        return prp_stm;
    }

    public static PreparedStatement GetNewDBPreparedStatement_Imports(Connection conn) throws SQLException {
        String q_insert = "INSERT INTO ImportsChanges VALUES(?,?,?,?,?,?,?,?)";
        PreparedStatement prp_stm = conn.prepareStatement(q_insert);
        return prp_stm;
    }


    static boolean SetupDB(Path result_path) {
        Connection conn = null;
        boolean success = false;
        try {
            logger.info("Setuping Database ...");
            conn = DriverManager.getConnection("jdbc:sqlite:" + result_path);
            Statement stmt = conn.createStatement();


            String q_dropTable_1 = "DROP TABLE IF EXISTS `APIReplacements`;";
            stmt.execute(q_dropTable_1);
            String q_dropTable_2 = "DROP TABLE IF EXISTS `ImportsChanges`;";
            stmt.execute(q_dropTable_2);

            String q_createTable_1 = "CREATE TABLE IF NOT EXISTS `APIReplacements` (id INTEGER, repo_name TEXT, default_branch TEXT, prev_commit TEXT, cur_commit TEXT, commit_message TEXT, old_method TEXT, new_method TEXT, count INTEGER, replacement_paths TEXT, old_method_decl_path TEXT, old_method_decl_line_start INTEGER, old_method_decl_line_end INTEGER, old_method_decl_body TEXT, diffRemovedText TEXT, diffAddedText TEXT, removedMethods TEXT, addedMethods TEXT,nRemovedMethods INTEGER, nAddedMethods INTEGER, nCandidateAPIs INTEGER, candidateAPIs TEXT, PRIMARY KEY(id))"; //  dependencyFileFound INTEGER, nDependencies INTEGER,
            stmt.execute(q_createTable_1);
            String q_createTable_2 = "CREATE TABLE IF NOT EXISTS `ImportsChanges` (id INTEGER, repo_name TEXT, default_branch TEXT, prev_commit TEXT, cur_commit TEXT, path TEXT, added TEXT, removed TEXT, PRIMARY KEY(id))";
            stmt.execute(q_createTable_2);

            success=true;
        } catch (SQLException e) {
            logger.error("Failed to setup DB", e);
        } finally {
            logger.info("Database setup FINISHED.");
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    logger.error("Database closing setup connection FAILED", e);
                }
            }
        }

        return success;
    }
}
