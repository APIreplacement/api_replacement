package com.anon.helpers;

import com.anonymous.parser.parser.db.MethodDeclarationInfoDB;
import com.anonymous.parser.parser.db.PackagesDeclarationDB;
import com.anonymous.parser.parser.ds.MethodDeclarationInfo;
import com.anonymous.parser.parser.ds.MethodInvocationInfo;
import com.anonymous.parser.parser.ds.PackageDeclarationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TargetApacheCommonsAPIHelper {

    private static final Logger logger = LoggerFactory.getLogger(TargetApacheCommonsAPIHelper.class);

    public static List<MethodDeclarationInfo> allAPIs = new ArrayList<>(); //only public method declarations (APIs)
    public static List<String/*base package name*/> packages = new ArrayList<>();

    public static void LoadAPIs(Path DB_PATH)
    {
        TargetApacheCommonsAPIHelper.allAPIs.clear();
        List<MethodDeclarationInfo> apis = MethodDeclarationInfoDB.ReadFromSqlite(DB_PATH);
        for(MethodDeclarationInfo m: apis)
            if(m.isPublic==1)
                allAPIs.add(m);
    }

    public static void LoadPackages(Path DB_PATH)
    {
        TargetApacheCommonsAPIHelper.packages.clear();
        Map<String, Set<PackageDeclarationInfo>> allPackagesPerProject = PackagesDeclarationDB.ReadPackagesFromSqlite_GroupByProject(DB_PATH);
        for(Map.Entry<String, Set<PackageDeclarationInfo>> entry: allPackagesPerProject.entrySet())
        {
            int minPackageLen = 99999;
            String minPackage = null;
            for (PackageDeclarationInfo pdi : entry.getValue()) {
                if(pdi.fullyQualifiedPackageName.length()<minPackageLen)
                {
                    minPackageLen = pdi.fullyQualifiedPackageName.length();
                    minPackage=pdi.fullyQualifiedPackageName;
                }
            }
            packages.add(minPackage);
        }
        return;
    }

    public static List<MethodDeclarationInfo> FindMatchingAPIs(MethodInvocationInfo method, Set<String> importedPackages)
    {
        if(importedPackages==null)
            return null;

        List<MethodDeclarationInfo> matches = new ArrayList<>();
        for(MethodDeclarationInfo m: allAPIs)
            if(m.name.equals(method.name) && m.nArgs==method.nArgs) {
                boolean foundAImportMatch=false;
                for(String anImportedPackage: importedPackages)
                    if(m.qualifiedClassName.contains(anImportedPackage)) {
                        foundAImportMatch = true;
                        break;
                    }
                if(foundAImportMatch)
                    matches.add(m);
            }
        return matches;
    }
}
