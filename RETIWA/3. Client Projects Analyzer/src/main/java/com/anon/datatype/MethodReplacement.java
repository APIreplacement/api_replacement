package com.anon.datatype;

import com.anon.cmdrunners.GitCmdRunner;
import com.anonymous.parser.parser.ds.MethodInvocationInfo;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public class MethodReplacement {
    public MethodInvocationInfo oldMethod;
    public MethodInvocationInfo newMethod;
    public int line_before, line_after;
    public GitCmdRunner.GitFilePath filePath;
    public int nRemovedMethods, nAddedMethods;
    public String removedCode, addedCode, removedMethods, addedMethods;
    public Set<String> importedPackages;

    public MethodReplacement(MethodInvocationInfo oldMethod, MethodInvocationInfo newMethod,
                             GitCmdRunner.GitFilePath filePath,
                             int line_before, int line_after,
                             String removedCode, String addedCode,
                             int nRemovedMethods, int nAddedMethods,
                             String removedMethods, String addedMethods) {
        this.oldMethod = oldMethod;
        this.newMethod = newMethod;
        this.filePath = filePath;
        this.line_before = line_before;
        this.line_after = line_after;
        this.removedCode = removedCode;
        this.addedCode = addedCode;
        this.nRemovedMethods = nRemovedMethods;
        this.nAddedMethods = nAddedMethods;
        this.removedMethods = removedMethods;
        this.addedMethods = addedMethods;
    }

    public void AddPackages(Set<String> _importedPackages)
    {
        importedPackages = _importedPackages;
    }

    @Override
    public String toString() {
        return "MethodReplacement{" +
                "oldMethod=" + oldMethod +
                ", newMethod=" + newMethod +
                ", line_before=" + line_before +
                ", line_after=" + line_after +
                ", filePath=" + filePath +
                ", nRemovedMethods=" + nRemovedMethods +
                ", nAddedMethods=" + nAddedMethods +
                ", removedCode='" + removedCode + '\'' +
                ", addedCode='" + addedCode + '\'' +
                ", removedMethods='" + removedMethods + '\'' +
                ", addedMethods='" + addedMethods + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MethodReplacement that = (MethodReplacement) o;

        Set<String> intersection = new HashSet<>(this.importedPackages);
        intersection.retainAll(that.importedPackages);

        return  this.oldMethod.equals(that.oldMethod) &&
                this.newMethod.equals(that.newMethod) &&
                false==intersection.isEmpty();
    }

    @Override
    public int hashCode() {
        return Objects.hash(oldMethod, newMethod);
    }
}