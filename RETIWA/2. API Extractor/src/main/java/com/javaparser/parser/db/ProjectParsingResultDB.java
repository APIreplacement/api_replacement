package com.javaparser.parser.db;

import com.javaparser.parser.ds.ProjectParsingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class ProjectParsingResultDB {
    private static final Logger logger = LoggerFactory.getLogger(ProjectParsingResultDB.class);

    public static ProjectParsingResult Merge(List<ProjectParsingResult> allResults) {
        ProjectParsingResult merged = new ProjectParsingResult("ALL", new ArrayList<>(), new ArrayList<>(),
                new HashSet<>(), new HashSet<>());

        for(ProjectParsingResult res: allResults)
        {
            if(res.methodInvocations==null)
                logger.warn("NULL res.methodInvocations for {}", res.projectName);
            else
                merged.methodInvocations.addAll(res.methodInvocations);

            if(res.methodDeclarations==null)
                logger.warn("NULL res.methodDeclarations for {}", res.projectName);
            else
                merged.methodDeclarations.addAll(res.methodDeclarations);

            if(res.packageDeclarations==null)
                logger.warn("NULL res.packageDeclarations for {}", res.projectName);
            else
                merged.packageDeclarations.addAll(res.packageDeclarations);

            if(res.userTypeDeclarations==null)
                logger.warn("NULL res.userTypeDeclarations for {}", res.projectName);
            else
                merged.userTypeDeclarations.addAll(res.userTypeDeclarations);
        }
        return merged;
    }

    public static void Report(ProjectParsingResult result)
    {
        logger.info("");
        logger.info("===================== REPORT ========================");
        if(result.methodInvocations!=null) {
            int sum = 0;
            for (var m : result.methodInvocations) sum += m.lineNumbers.size();
            logger.info("{} Method Invocations Found ({} unique <file,method>)", sum, result.methodInvocations.size());
        }
        if(result.methodDeclarations!=null)
            logger.info("{} Method Declaration Found", result.methodDeclarations.size());
        if(result.packageDeclarations!=null)
            logger.info("{} Packages declared", result.packageDeclarations.size());
        if(result.userTypeDeclarations!=null)
            logger.info("{} UserType declared",result.userTypeDeclarations.size());
        logger.info("======================================================");
        logger.info("");
    }

    public static void WriteToSQLite(ProjectParsingResult result, Path path)
    {
        WriteToSQLite(result, path, false);
    }

    public static void WriteToSQLite(ProjectParsingResult result, Path path, boolean withLineTable)
    {
        if(result==null)
            logger.error("Writing Result Failed: NULL result");
        if(result.methodInvocations!=null) {
            logger.info("Writing Result on DB 1/5: Method Invocations...");
            MethodInvocationInfoDB.WriteToSqlite(result.methodInvocations, true, withLineTable, false, path.toString());
        }
        if(result.methodDeclarations!=null) {
            logger.info("Writing Result on DB 2/5: Method Declarations...");
            MethodDeclarationInfoDB.WriteToSqlite(result.methodDeclarations, false, path.toString());
        }
        if(result.packageDeclarations!=null) {
            logger.info("Writing Result on DB 3/5: Packages...");
            PackagesDeclarationDB.WriteToSQLite(result.packageDeclarations, path.toString());
        }
        if(result.userTypeDeclarations!=null) {
            logger.info("Writing Result on DB 4/5: User Defined Types...");
            UserTypeDeclarationDB.WriteToSQLite(result.userTypeDeclarations, path.toString());
        }
        if(result.javaFiles!=null)
        {
            logger.info("Writing Result on DB 5/5: Java File Content...");
            JavaFileInfoDB.WriteToSQLite(result.javaFiles, path, false);
        }

    }

    public static ProjectParsingResult ReadFromSqlite(String projectName, Path path) {
        ProjectParsingResult res = new ProjectParsingResult(projectName);
        res.methodInvocations = MethodInvocationInfoDB.ReadFromSqlite(path,true, new HashSet<>());
        res.methodDeclarations = MethodDeclarationInfoDB.ReadFromSqlite(path);
        // TODO res.userTypeDeclarations = UserTypeDeclarationDB.readAllRepoTypes(path.toString());
        // TODO res.packageDeclarations = PackagesDeclarationDB.readAllRepoPackages(path.toString());
        return null;
    }
}
