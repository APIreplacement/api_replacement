package com.anon.datatype;

import com.anonymous.parser.parser.ds.MethodDeclarationInfo;
import com.anonymous.parser.parser.ds.MethodInvocationInfo;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class ProjectMethods {
    public Map<Path, Set<MethodDeclarationInfo>> methodsDeclarationsInEachFile;
    public Map<Path, Set<MethodInvocationInfo>> methodsCallsInEachFile;

    public ProjectMethods() {
        this.methodsDeclarationsInEachFile = new HashMap<>();
        this.methodsCallsInEachFile = new HashMap<>();
    }

    public ProjectMethods(ProjectMethods rh) {
        if(rh != null) {
            this.methodsDeclarationsInEachFile = new HashMap<>(rh.methodsDeclarationsInEachFile);
            this.methodsCallsInEachFile = new HashMap<>(rh.methodsCallsInEachFile);
        }
        else
        {
            this.methodsDeclarationsInEachFile = new HashMap<>();
            this.methodsCallsInEachFile = new HashMap<>();
        }
    }

    public int CountTotalMethodDeclarations()
    {
        int nTotalMethodDecls = 0;
        for (var t : methodsDeclarationsInEachFile.values())
            nTotalMethodDecls += t.size();
        return nTotalMethodDecls;
    }

    public int CountTotalMethodCalls()
    {
        int nTotalMethodCalls = 0;
        for (var t : methodsCallsInEachFile.values())
            nTotalMethodCalls += t.size();
        return nTotalMethodCalls;
    }
}
