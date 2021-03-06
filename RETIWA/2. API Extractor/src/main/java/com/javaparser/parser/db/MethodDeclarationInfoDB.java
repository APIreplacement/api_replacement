package com.javaparser.parser.db;

import com.javaparser.parser.ds.MethodDeclarationInfo;
import com.javaparser.parser.ds.MethodInvocationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TABLE 1: MethodDeclaration
 *  method_decl_id, project_name, path, line_start, line_end, isPublic INTEGER, qualified_class_name, method_name, return_type, n_args, args_types, has_javadoc, javadoc
 */
public class MethodDeclarationInfoDB {
    private static final Logger logger = LoggerFactory.getLogger(MethodDeclarationInfoDB.class);

    public static final String TABLE_DECLARATIONS_MAIN = "MethodDeclaration";

    /**
     *
     * @param declarationsList
     * @param useDatabaseIdFieldAsIds   Whether we should use {@link MethodInvocationInfo#databaseId} or newly created IDs.
     *                                  Reusing existing databaseId comes in handy when we are re-storing a loaded data
     *                                  and we want to ensure ID consistency between to-be-written db and older ones.
     * @param path
     */
    public static void WriteToSqlite(List<MethodDeclarationInfo> declarationsList, boolean useDatabaseIdFieldAsIds, String path)
    {
        if(declarationsList==null) return;

        Connection conn = null;
        String sqlitePath = "jdbc:sqlite:" + path;
        try {

            logger.info("Writing Method Declaration at {}", path);

            File parentDir = new File(path).getParentFile();
            if(parentDir.exists() == false)
                parentDir.mkdirs();


            conn = DriverManager.getConnection(sqlitePath);
            conn.setAutoCommit(false); // --> you should call conn.commit();
            java.sql.Statement stmt = conn.createStatement();



            String q_drop = String.format("DROP TABLE IF EXISTS %s;", TABLE_DECLARATIONS_MAIN);
            String q_create = String.format("CREATE TABLE IF NOT EXISTS %s (method_decl_id INTEGER, project_name TEXT, commit_sha TEXT, path TEXT, line_start INTEGER, line_end INTEGER, signature_line INTEGER,  isPublic INTEGER, isConstructor INTEGER, is_deprecated INTEGER, has_body INTEGER, qualified_class_name TEXT, method_name TEXT, return_type TEXT, n_args INTEGER, args_types TEXT, method_body TEXT, has_javaDoc INTEGER, javadoc_line_start INTEGER, javadoc_line_end INTEGER, javadoc TEXT, remark INTEGER, remark_str TEXT)", TABLE_DECLARATIONS_MAIN);



            //if(overwrite)
            stmt.execute(q_drop );
//            else
//            {
//                ResultSet rs = stmt.executeQuery(String.format("SELECT COUNT(*) FROM %s", TABLE_DECLARATIONS_MAIN));
//                rs.next();
//                methodDeclarationIndex = rs.getInt(1);
//            }
            stmt.execute(q_create ); //create the table

            String q_insert = String.format("INSERT INTO %s VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", TABLE_DECLARATIONS_MAIN);

            PreparedStatement pstmt = conn.prepareStatement(q_insert);

            final int BATCH_SIZE = 500000, total_declarations = declarationsList.size();
            int lastReportedProgress = -1, currentProgress;
            for(int methodDeclIndex=0; methodDeclIndex<total_declarations; methodDeclIndex++)
            {

                currentProgress = (int)((methodDeclIndex*100.0)/ total_declarations);
                if(currentProgress-lastReportedProgress>= 1)
                {
                    lastReportedProgress = currentProgress;
                    //logger.info("%{} ({}/{})", currentProgress, methodDeclIndex, total_declarations-1);
                }

                MethodDeclarationInfo methodDecInfo = declarationsList.get(methodDeclIndex);

                pstmt.setInt(1, useDatabaseIdFieldAsIds?methodDecInfo.databaseId:methodDeclIndex);
                pstmt.setString(2, methodDecInfo.projectName);
                pstmt.setString(3, methodDecInfo.commitSHA);
                pstmt.setString(4,methodDecInfo.fileRelativePath);
                pstmt.setInt(5, methodDecInfo.lineStart);
                pstmt.setInt(6, methodDecInfo.lineEnd);
                pstmt.setInt(7, methodDecInfo.signatureLine);

                pstmt.setInt(8, methodDecInfo.isPublic);
                pstmt.setInt(9, methodDecInfo.isConstructor?1:0);
                pstmt.setInt(10, methodDecInfo.isDeprecated?1:0);
                pstmt.setInt(11, methodDecInfo.hasBody?1:0);

                pstmt.setString(12,methodDecInfo.qualifiedClassName);
                pstmt.setString(13,methodDecInfo.name);
                pstmt.setString(14,methodDecInfo.returnType);
                pstmt.setInt(15,methodDecInfo.nArgs);
                pstmt.setString(16,methodDecInfo.argsTypes);
                pstmt.setString(17,methodDecInfo.declarationCode_generated);

                pstmt.setInt(18,methodDecInfo.hasJavaDoc==true?1:0);
                pstmt.setInt(19,methodDecInfo.javaDocStartLine);
                pstmt.setInt(20,methodDecInfo.javaDocEndLine);
                pstmt.setString(21,methodDecInfo.javaDoc);
                pstmt.setInt(22,methodDecInfo.remark);
                pstmt.setString(23,methodDecInfo.remark_str);

                pstmt.addBatch();

                if(methodDeclIndex%BATCH_SIZE==0 || methodDeclIndex+1==declarationsList.size())
                    pstmt.executeBatch();
            }
            conn.commit();

        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            try {
                if (conn != null) {
                    conn.close();
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    public static List<MethodDeclarationInfo> ReadFromSqlite(Path path)
    {
        Map<String, List<MethodDeclarationInfo>> res = ReadFromSqlite_GroupByProject(path);
        if(res==null)
            return null;
        List<MethodDeclarationInfo> flatten = new ArrayList<>();
        for(var v: res.values())
            flatten.addAll(v);
        return flatten;
    }

    /**
     * Corresponding method to {@link #WriteToSqlite}
     */
    public static Map<String/*project-name*/, List<MethodDeclarationInfo>> ReadFromSqlite_GroupByProject(Path path) {
        if(!Files.exists(path) || !Files.isRegularFile(path)) {
            logger.error("Database not found at {}", path);
            return null;
        }

        Map<String, List<MethodDeclarationInfo>> all = new HashMap<>();
        Connection conn = null;
        String sqlitePath = "jdbc:sqlite:" + path;
        try {
            conn = DriverManager.getConnection(sqlitePath);
            Statement stmt = conn.createStatement();


            String query =  String.format("SELECT * FROM %s", TABLE_DECLARATIONS_MAIN);
            ResultSet rs = stmt.executeQuery(query);
            while (rs.next())
            {
                int id = rs.getInt("method_decl_id");
                String projectName = rs.getString("project_name");
                String commitSHA = rs.getString("commit_sha");
                String filePath = rs.getString("path");
                int method_lineStart = rs.getInt("line_start");
                int method_lineEnd = rs.getInt("line_end");
                int signatureLineNumber = rs.getInt("signature_line");
                int isPublic = rs.getInt("isPublic");
                boolean isConstructor = rs.getInt("isConstructor")==1;
                boolean isDeprecated = rs.getInt("is_deprecated")==1;
                boolean hasBody = rs.getInt("has_body")==1;
                String qualifiedClassName = rs.getString("qualified_class_name");
                String methodName = rs.getString("method_name");
                String returnType = rs.getString("return_type");
                if(returnType.equals("")) returnType="void";
                int nArgs = rs.getInt("n_args");
                String argsTypes = rs.getString("args_types");
                String methodBody = rs.getString("method_body");

                boolean hasJavaDoc = rs.getInt("has_javaDoc")==1;
                int javadoc_lineStart = rs.getInt("javadoc_line_start");
                int javadoc_lineEnd = rs.getInt("javadoc_line_end");
                String javadoc = rs.getString("javadoc");
                int remark = rs.getInt("remark");
                String remark_str = rs.getString("remark_str");


                MethodDeclarationInfo info = new MethodDeclarationInfo(qualifiedClassName, returnType, methodName,
                        nArgs, argsTypes, method_lineStart, method_lineEnd, signatureLineNumber);
                info.databaseId = id;
                info.projectName = projectName;
                info.commitSHA = commitSHA;
                info.fileRelativePath = filePath;
                info.isPublic = isPublic;
                info.isConstructor = isConstructor;
                info.isDeprecated = isDeprecated;
                info.hasBody = hasBody;
                info.hasJavaDoc = hasJavaDoc;
                info.declarationCode_generated = methodBody;
                info.javaDocStartLine = javadoc_lineStart;
                info.javaDocEndLine = javadoc_lineEnd;
                info.javaDoc = javadoc;
                info.remark = remark;
                info.remark_str = remark_str;

                if(all.containsKey(projectName))
                {
                    List<MethodDeclarationInfo> list = all.get(projectName);
                    list.add(info);
                }
                else
                {
                    List<MethodDeclarationInfo> list_new = new ArrayList<>();
                    list_new.add(info);
                    all.put(projectName, list_new);
                }

            }

        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            try {
                if (conn != null) {
                    conn.close();
                }
            } catch (SQLException ex) {
                ex.printStackTrace();
            }
        }

        return all;
    }
}
