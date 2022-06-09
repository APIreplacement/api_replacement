package com.javaparser.parser.helper;

import com.javaparser.parser.ds.MethodInvocationInfo;
import com.javaparser.parser.ds.ProjectParsingResult;
import com.javaparser.parser.ds.UserTypeDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResolveLocalMethodInvocations {
    private static final Logger logger = LoggerFactory.getLogger(ResolveLocalMethodInvocations.class);

    public static void Resolve(ProjectParsingResult result)
    {
        for(MethodInvocationInfo method: result.methodInvocations)
        {
            boolean isLocalCall = false;
            for(UserTypeDeclaration t: result.userTypeDeclarations)
            {
                if(method.qualifiedClassName.equals(t.fullyQualifiedName))
                {
                    isLocalCall = true;
                    break;
                }
            }
            if(isLocalCall)
                method.isLocalInvocation = true;
        }
    }
}
