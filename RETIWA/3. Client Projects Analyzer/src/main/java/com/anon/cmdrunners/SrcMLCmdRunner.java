package com.anon.cmdrunners;

import com.anonymous.parser.parser.ds.MethodDeclarationInfo;
import com.anonymous.parser.parser.ds.MethodInvocationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.w3c.dom.Node.*;

/**
 * Always check for latest version of this class at: https://gist.github.com/emadpres/2334cc71e27ccc5055b062538c25f111
 *
 * Version: 2.1 (2021-11-05)
 * Change log:
 * - Adding 120sec timeout
 *
 * NOTE: This class relies on `CmdRunner` (available at: https://gist.github.com/emadpres/2334cc71e27ccc5055b062538c25f111)
 */
public class SrcMLCmdRunner {
    private static final Logger logger = LoggerFactory.getLogger(SrcMLCmdRunner.class);
    private static final int TIMEOUT_SEC = 120;

    public static void main(String[] args) {
        List<MethodInvocationInfo> res = ExtractMethodsCallsFromText("BBB.CCC<ct>.Function<Generic.Type>(12)", "Java", null);
        int dummy = res.size();
    }


    /**
     * If the `codeFile` has an explicit extension (like, .java), you do not need to pass `languageExtension`.
     * @param realFilePath  file path to be added to the results (for cases when parsed file is in a temp location)
     * @param languageExtension     Case-sensitive; Allowable values:  C, C++, C#, and Java
     * @param onlyMethodsUnderClass    if True, we look for "*>class>block>method" paths. If false, we look for "*>method".
     *                                 When java source code is not fully supported the scope of class is detected wrongly and
     *                                 some methods go out of class. So, to make sure all methods are detected you can set
     *                                 this argument off.
     */
    public static List<MethodDeclarationInfo> ExtractMethodsDeclarations(Path codeFile, String languageExtension, Path realFilePath, boolean onlyMethodsUnderClass) {
        List<String> command = new ArrayList<>();
        command.add("srcml");
        command.add(codeFile.toString());
        if(languageExtension!=null && !languageExtension.isEmpty())
        {
            command.add("--language");
            command.add(languageExtension);
        }
        command.add("--position");
        command.add("--no-xml-declaration");
        command.add("--xpath");
        if(onlyMethodsUnderClass)
            command.add("//*[name()='enum' or name()='class' or name()='interface']/src:block/*[name()='function' or name()='constructor' or name()='function_decl']");
        else
            command.add("//*[name()='function' or name()='constructor' or name()='function_decl']");

        String output = CmdRunner.getInstance().RunCommand_ReturnOutput(command, null,null, TIMEOUT_SEC);
        if(SrcMLOutputHasError(output))
            return new ArrayList<>();

        List<MethodDeclarationInfo> res = _ProcessExtractedMethodDeclarations(output, realFilePath);
        return res;
    }

    private static List<MethodDeclarationInfo> _ProcessExtractedMethodDeclarations(String srcmlOutput, Path _filePath)
    {
        /** ----------------------
         *  Sample srcmlOutput:
         *  ----------------------
         * <unit xmlns="http://www.srcML.org/srcML/src" xmlns:pos="http://www.srcML.org/srcML/position" revision="1.0.0" pos:tabs="8">
         *       <unit>
         *          ...
         *      </unit>
         *      <unit revision="1.0.0" language="Java" filename="/Users/emadpres/Downloads/test.java" pos:tabs="8" item="1">
         *         <constructor pos:start="39:5" pos:end="47:5">
         *             <name pos:start="39:5" pos:end="39:18">PercolateQuery</name>
         *             <parameter_list pos:start="39:19" pos:end="39:46">
         *                 (
         *                 <parameter pos:start="39:20" pos:end="39:38"><decl pos:start="39:20" pos:end="39:38"><type pos:start="39:20" pos:end="39:38"><name pos:start="39:20" pos:end="39:25">String</name></type> <name pos:start="39:27" pos:end="39:38">documentType</name></decl></parameter>
         *                 ,
         *                 <parameter pos:start="39:41" pos:end="39:45"><decl pos:start="39:41" pos:end="39:45"><type pos:start="39:41" pos:end="39:45"><name pos:start="39:41" pos:end="39:43">int</name></type> <name pos:start="39:45" pos:end="39:45">x</name></decl></parameter>
         *                 )
         *             </parameter_list>
         *             <block pos:start="39:48" pos:end="47:5">{
         *                 <block_content pos:start="40:9" pos:end="46:81">
         *                 </block_content>}
         *             </block>
         *         </constructor>
         *     </unit>
         *      <unit>
         *          ...
         *      </unit>
         * </unit>
         */

        ArrayList<MethodDeclarationInfo> res = new ArrayList<>();
        if(srcmlOutput.lines().count()==1)
            return res;

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db;
        try {
            db = dbf.newDocumentBuilder();
            Document doc = db.parse(new InputSource(new StringReader(srcmlOutput)));
            doc.getDocumentElement().normalize();
            Node rootUnit = doc.getFirstChild();
            NodeList units = ((Element) rootUnit).getElementsByTagName("unit");
            for(int i=0; i<units.getLength(); i++)
            {
                try {
                    Node item = units.item(i);
                    Node aMethodDecl = item.getFirstChild();

                    String start = ((Element) aMethodDecl).getAttributeNode("pos:start").getValue().split(":")[0];
                    String end = ((Element) aMethodDecl).getAttributeNode("pos:end").getValue().split(":")[0];

                    String name = null;
                    int nParams = -1;
                    boolean nArgsVariable = false;
                    NodeList children = aMethodDecl.getChildNodes();
                    for(int j=0; j<children.getLength(); j++)
                    {
                        Node aChild = children.item(j);
                        if(aChild.getNodeType() != ELEMENT_NODE)
                            continue;
                        if( ((Element)aChild).getTagName().equals("name") )
                            name = aChild.getTextContent();
                        else if( ((Element)aChild).getTagName().equals("parameter_list") ) {
                            nParams = ((Element) aChild).getElementsByTagName("parameter").getLength();
                            nArgsVariable = aChild.getTextContent().contains("...");
                            if(nArgsVariable)
                                nParams -= 1; //the variable with "..." is 0 or more
                        }
                    }
                    if(name!=null && nParams!=-1) { // there are bad examples where srcML fails to parse
                        try {
                            int ls = Integer.parseInt(start);
                            int le = Integer.parseInt(end);
                            MethodDeclarationInfo mdi = new MethodDeclarationInfo(null, null, name, nParams, null, ls, le, -1);
                            mdi.fileRelativePath = String.valueOf(_filePath);
                            mdi.arbitraryNumberOfArguments=nArgsVariable;
                            res.add(mdi);
                        } catch (Exception e)
                        {
                            logger.error("Failed parsing method call: {}-{}--{}(#{}) FILE={}",start, end, name,nParams, _filePath, e);
                        }
                    }
                }
                catch (Exception e)
                {
                    logger.error("Failed parsing method declarations unit #{} FILE={}", i, _filePath, e);
                }
            }

        } catch (ParserConfigurationException | IOException | SAXException e) {
            logger.error("Failed parsing method declarations FILE={}",_filePath, e);
        }

        return res;
    }


    /**
     * If the `codeFile` has an explicit extension (like, .java), you do not need to pass `languageExtension`.
     * @param realFilePath  file path to be added to the results (for cases when parsed file is in a temp location)
     * @param languageExtension     Case-sensitive; Allowable values:  C, C++, C#, and Java
     */
    public static List<ClassInfo> ExtractClassDeclarations(Path codeFile, String languageExtension, Path realFilePath) {
        List<String> command = new ArrayList<>();
        command.add("srcml");
        command.add(codeFile.toString());
        if(languageExtension!=null && !languageExtension.isEmpty())
        {
            command.add("--language");
            command.add(languageExtension);
        }
        command.add("--position");
        command.add("--no-xml-declaration");
        command.add("--xpath");
        command.add("//*[name()='enum' or name()='class' or name()='interface']/src:name/text()|//*[name()='enum' or name()='class' or name()='interface']/@*");

        String output = CmdRunner.getInstance().RunCommand_ReturnOutput(command, null,null, TIMEOUT_SEC);
        if(SrcMLOutputHasError(output))
            return new ArrayList<>();

        List<ClassInfo> res = _ProcessExtractedClassDeclarations(output, realFilePath);
        return res;
    }

    private static List<ClassInfo> _ProcessExtractedClassDeclarations(String srcmlOutput, Path _filePath)
    {
        /** ----------------------
         *  Sample srcmlOutput:
         *  ----------------------
         <unit>
             <unit item="1">start="90:1"</unit>
             <unit item="2">end="3959:1"</unit>
             <unit item="3">ActiveServices</unit>

             <unit item="4">start="156:46"</unit>    <-------- Anonymous class
             <unit item="5">end="162:5"</unit>

             <unit item="6">start="168:5"</unit>
             <unit item="7">end="182:5"</unit>
             <unit item="8">ActiveForegroundApp</unit>
         </unit>
         */

        List<ClassInfo> res = new ArrayList<>();
        Matcher matcher = Pattern.compile("<unit .*>(:?start=\"(\\d+):\\d+\"|end=\"(\\d+):\\d+\"|\\w+)</unit>").matcher(srcmlOutput);


        String class_name;
        String line_start_num, line_end_num;
        boolean f;

        // How this loop works? When loop starts, we assume value of matcher refers to start line
        // This way if at the end of the loop we find() and instead of "class name" we get "start" we just perform
        // "continue;" command we don't need to worry about moving Matcher head backward
        f = matcher.find();
        while(f)
        {
            do {
                // Look for a match where its 2nd capturing group is not null.
                // At the same time, check we have match (f==true)
                line_start_num = matcher.group(2);
                f = matcher.find();
            } while(line_start_num==null && f!=false);

            if(f==false)
                break; // end of units

            line_end_num = matcher.group(3);

            f = matcher.find();
            if(f==false)
                break; //happens when last class in the output list is anonymous
            class_name = matcher.group(1);

            if(class_name.startsWith("start=") == false)
            {
                // we find a case of anonymous class (only start,end, but no class name)
                try {
                    int ls = Integer.parseInt(line_start_num);
                    int le = Integer.parseInt(line_end_num);
                    res.add(new ClassInfo(_filePath, class_name, ls, le));
                } catch (Exception e)
                {
                    logger.error("Failed parsing class declaration: {}-{}--{} FILE={}",line_start_num, line_end_num, class_name, _filePath, e);
                }
            }
        }

        return res;
    }


    /**
     * If the `codeFile` has an explicit extension (like, .java), you do not need to pass `languageExtension`.
     * @param realFilePath  file path to be added to the results (for cases when parsed file is in a temp location)
     * @param languageExtension     Case-sensitive; Allowable values:  C, C++, C#, and Java
     * @param onlyMethodsUnderClass    if True, we look for "*>class>block>method" paths. If false, we look for "*>method".
     *                                 When java source code is not fully supported the scope of class is detected wrongly and
     *                                 some methods go out of class. So, to make sure all methods are detected you can set
     *                                 this argument off.
     */
    public static List<MethodInfo> ExtractMethodsDeclarations_withoutNumArgs(Path codeFile, String languageExtension, Path realFilePath, boolean onlyMethodsUnderClass) {
        List<String> command = new ArrayList<>();
        command.add("srcml");
        command.add(codeFile.toString());
        if(languageExtension!=null && !languageExtension.isEmpty())
        {
            command.add("--language");
            command.add(languageExtension);
        }
        command.add("--position");
        command.add("--no-xml-declaration");
        command.add("--xpath");
        if(onlyMethodsUnderClass)
            command.add("//*[name()='enum' or name()='class' or name()='interface']/src:block/*[name()='function' or name()='constructor' or name()='function_decl']/src:name/text()|//*[name()='enum' or name()='class' or name()='interface']/src:block/*[name()='function' or name()='constructor' or name()='function_decl']/@*");
        else
            command.add("//*[name()='function' or name()='constructor' or name()='function_decl']/src:name/text()|//*[name()='function' or name()='constructor' or name()='function_decl']/@*");

        String output = CmdRunner.getInstance().RunCommand_ReturnOutput(command, null,null, TIMEOUT_SEC);
        if(SrcMLOutputHasError(output))
            return new ArrayList<>();

        List<MethodInfo> res = _ProcessExtractedMethodDeclarations_WithoutNumArgs(output, realFilePath);
        return res;
    }

    private static List<MethodInfo> _ProcessExtractedMethodDeclarations_WithoutNumArgs(String srcmlOutput, Path _filePath)
    {
        /** ----------------------
         *  Sample srcmlOutput:
         *  ----------------------
         * <unit xmlns="http://www.srcML.org/srcML/src" xmlns:pos="http://www.srcML.org/srcML/position" revision="1.0.0" pos:tabs="8">
         *
         * <unit revision="1.0.0" language="Java" filename="main.Main.java" pos:tabs="8" item="1">start="18:5"</unit>
         * <unit revision="1.0.0" language="Java" filename="main.Main.java" pos:tabs="8" item="2">end="65:5"</unit>
         * <unit revision="1.0.0" language="Java" filename="main.Main.java" pos:tabs="8" item="3">main</unit>
         *
         * <unit revision="1.0.0" language="Java" filename="main.Main.java" pos:tabs="8" item="4">start="71:5"</unit>
         * <unit revision="1.0.0" language="Java" filename="main.Main.java" pos:tabs="8" item="5">end="104:5"</unit>
         * <unit revision="1.0.0" language="Java" filename="main.Main.java" pos:tabs="8" item="6">ReadListOfImpactedFiles</unit>
         *
         * </unit>
         */
        List<MethodInfo> res = new ArrayList<>();
        Matcher start_lines = Pattern.compile("<unit .*>start=\"(\\d+):\\d+\"</unit>").matcher(srcmlOutput);
        Matcher end_lines = Pattern.compile("<unit .*>end=\"(\\d+):\\d+\"</unit>").matcher(srcmlOutput);
        Matcher method_names = Pattern.compile("<unit .*>([\\$\\w]*)</unit>").matcher(srcmlOutput);

        while(true)
        {
            boolean a = start_lines.find();
            boolean b = end_lines.find();
            boolean c = method_names.find();
            if(a!=b || b!=c) {
                // It's possible due to fact that SrcML support only Java 8 syntax.
                //System.err.println("Mismatching between regex results count! ***************************");
                return new ArrayList<>();
            }

            if(a==false)
                break; // no more matches. break the while

            String line_start_num = start_lines.group(1);
            String line_end_num = end_lines.group(1);
            String method_name = method_names.group(1);


            try {
                int ls = Integer.parseInt(line_start_num);
                int le = Integer.parseInt(line_end_num);
                res.add(new MethodInfo(ls, le, method_name, -1, _filePath));
            } catch (Exception e)
            {
                logger.error("Failed parsing method declaration: {}-{}--{} FILE={}",line_start_num, line_end_num, method_name, _filePath, e);
            }

        }
        return res;
    }

    /**
     * If the `codeFile` has an explicit extension (like, .java), you do not need to pass `languageExtension`.
     * @param realFilePath  file path to be added to the results (for cases when parsed file is in a temp location)
     * @param languageExtension     Case-sensitive; Allowable values:  C, C++, C#, and Java
     */
    public static List<MethodInvocationInfo> ExtractMethodsCalls(Path codeFile, String languageExtension, Path realFilePath) {
        List<String> command = new ArrayList<>();
        command.add("srcml");
        command.add(codeFile.toString());
        if(languageExtension!=null && !languageExtension.isEmpty())
        {
            command.add("--language");
            command.add(languageExtension);
        }
        command.add("--position");
        command.add("--no-xml-declaration");
        command.add("--xpath");
//        command.add("//src:call/src:name/text()|//src:call/src:name/src:name[last()]/text()");
        command.add("//src:call");

        String output = CmdRunner.getInstance().RunCommand_ReturnOutput(command, null,null, TIMEOUT_SEC);
        if(SrcMLOutputHasError(output))
            return new ArrayList<>();

        List<MethodInvocationInfo> res = _ProcessExtractedMethodCalls(output, realFilePath);
        return res;
    }

    /**
     * @param languageExtension     Case-sensitive; Allowable values:  C, C++, C#, and Java
     */
    public static List<MethodInvocationInfo> ExtractMethodsCallsFromText(String codeText, String languageExtension, Path filePath) {
        List<String> command = new ArrayList<>();
        command.add("srcml");
        command.add(String.format("--text=%s", codeText));
        command.add("--language");
        command.add(languageExtension);
        command.add("--position");
        command.add("--no-xml-declaration");
        command.add("--xpath");
        command.add("//src:call");
        //String output = CmdRunner.getInstance().RunCommandAndReturnOutput(command, null, 30);
        String output = CmdRunner.getInstance().RunCommand_ReturnOutput(command, null,null, TIMEOUT_SEC);
        if(SrcMLOutputHasError(output))
            return new ArrayList<>();

        List<MethodInvocationInfo> res = _ProcessExtractedMethodCalls(output, filePath);
        return res;
    }


    private static List<MethodInvocationInfo> _ProcessExtractedMethodCalls(String srcmlOutput, Path _filePath)
    {
        /**
         *  Sample Input 1:
         *      Function(12)
         *  Sample Output 1:
         *      <unit>
         *      	<call>
         *      		<name>Function</name>
         *      	    -------------------------------
         *      		<argument_list>(<argument><expr><literal type="number">12</literal></expr></argument>)</argument_list>
         *      	</call>
         *      </unit>
         *
         *  Sample Input 2:
         *      Function<A>(12)
         *  Sample Output 2:
         *      <call>
         *      	<name>
         *      		<name>Function</name>
         *      		<argument_list type="generic">
         *      			&lt;<argument><name>A</name></argument>&gt;
         *      		</argument_list>
         *      	</name>
         *          -------------------------------
         *      	<argument_list>(<argument><expr><literal type="number">12</literal></expr></argument>)</argument_list>
         *      </call>
         *
         *      </unit>
         *
         *
         *  Sample Input 3:
         *      BBB.CCC<ct>.Function<Generic.Type>(12)
         *  Sample Output 3:
         *      <unit>
         *          <call>
         *              <name>
         *                  <name>BBB</name>
         *
         *                  <operator>.</operator>
         *                  <name>
         *                      <name>CCC</name>
         *                      <argument_list type="generic">
         *                          &lt;<argument><name>ct</name></argument>&gt;
         *                      </argument_list>
         *                  </name>
         *
         *                  <operator>.</operator>
         *                  <name>
         *                      <name>Function</name>
         *                      <argument_list type="generic">&lt;
         *                          <argument><name><name>Generic</name><operator>.</operator><name>Type</name></name></argument>&gt;
         *                      </argument_list>
         *                  </name>
         *              </name>
         *              -------------------------------
         *              <argument_list>(<argument><expr><literal type="number">12</literal></expr></argument>)</argument_list>
         *          </call>
         *      </unit>
         */

        ArrayList<MethodInvocationInfo> res = new ArrayList<>();
        if(srcmlOutput.lines().count()==1)
            return res;

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db = null;
        try {
            db = dbf.newDocumentBuilder();
            Document doc = db.parse(new InputSource(new StringReader(srcmlOutput)));
            doc.getDocumentElement().normalize();
            Node rootUnit = doc.getFirstChild();
            NodeList unitCalls = rootUnit.getChildNodes();
            for(int i=0; i<unitCalls.getLength(); i++)
            {
                try {
                    Node aUnitCall = unitCalls.item(i);
                    if (aUnitCall.getNodeType() != ELEMENT_NODE) //ELEMENT_NODE refers to a <Tag>...</Tag>
                        continue;

                    Node aCall = aUnitCall.getFirstChild();

                    String methodName = null, startLine = null;
                    int nArgs = -1;

                    // aCall has two children: 1.name, 2.argument_list
                    for (Node callChild = aCall.getFirstChild(); callChild != null; callChild = callChild.getNextSibling()) {
                        if (!(callChild instanceof Element))
                            continue;

                        if (callChild.getNodeName().equals("name")) {
                            // 1/2 child: name
                            Node nameNode = callChild;
                            methodName = nameNode.getTextContent();
                            // Loop below checks if <name> has children; if so, it finds the last <name> children and uses
                            // its TEXT and repeat the process until <name> has no children
                            while (nameNode != null) {
                                NodeList nameChildren = nameNode.getChildNodes();
                                nameNode = null;
                                for (int j = nameChildren.getLength() - 1; j >= 0; j--) {
                                    Node nameChild = nameChildren.item(j);
                                    if (nameChild.getNodeName().equals("name")) {
                                        nameNode = nameChild;
                                        methodName = nameNode.getTextContent();
                                        break;
                                    }
                                }
                            }
                            if (methodName.contains("."))
                                System.err.printf("\n\n************** CHECK _ProcessExtractedMethodCalls method; output=%s for input: %s\n**********************\n\n", methodName, srcmlOutput);

                        } else if (callChild.getNodeName().equals("argument_list")) {
                            // 2/2 child: argument_list
                            Element argNode = (Element) callChild;
                            startLine = argNode.getAttributeNode("pos:start").getValue().split(":")[0];
                            //endLine = argNode.getAttributeNode("pos:end").getValue().split(":")[0];

                            nArgs = 0;
                            NodeList args = argNode.getChildNodes();
                            for (int j = 0; j < args.getLength(); j++) {
                                Node aArg = args.item(j);
                                if (aArg.getNodeType() != ELEMENT_NODE)
                                    continue;
                                if (((Element) aArg).getTagName().equals("argument"))
                                    nArgs++;
                            }
                        }
                    }

                    if(methodName!=null && startLine!=null && nArgs!=-1) {
                        try {
                            int ls = Integer.parseInt(startLine);
                            MethodInvocationInfo mii = new MethodInvocationInfo(null, null, methodName, nArgs, null);
                            mii.lineNumbers.add(ls);
                            mii.fileRelativePath = String.valueOf(_filePath);
                            res.add(mii);
                        } catch (Exception e)
                        {
                            logger.error("Failed parsing method call: {}--{} FILE={}",startLine, methodName, _filePath, e);
                        }
                    }

                } catch (Exception e)
                {
                    logger.error("Failed parsing method calls: unitCalls #{} FILE={}",i, _filePath, e);
                }

            }

        } catch (ParserConfigurationException | IOException | SAXException e) {
            logger.error("Failed parsing method calls FILE={}",_filePath, e);
        }

        return res;
    }

    /**
     * Why do we check error in this way? Because "srcml" command always return 0 error-code, even when there is error!
     * @param srcMLOutput The output retreived from "srcml" command (on console)
     */
    private static boolean SrcMLOutputHasError(String srcMLOutput)
    {
        final String SRCML_BAD_INPUT_ERR = "srcml: Unable to open file";
        final String SRCML_FAILED_TRANSLATING = "srcML translator error";
        final String SRCML_FAILED_PARSING = "parser error";


        if(srcMLOutput==null || srcMLOutput.startsWith(SRCML_BAD_INPUT_ERR) || srcMLOutput.startsWith(SRCML_FAILED_TRANSLATING) || srcMLOutput.contains(SRCML_FAILED_PARSING))
            return true;
        return false;
    }

    public static class ClassInfo {
        public Path filePath;
        public String className;
        public int lineStart, lineEnd;

        public ClassInfo(Path filePath, String className, int lineStart, int lineEnd) {
            this.filePath = filePath;
            this.className = className;
            this.lineStart = lineStart;
            this.lineEnd = lineEnd;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ClassInfo classInfo = (ClassInfo) o;
            return className.equals(classInfo.className);
        }

        @Override
        public int hashCode() {
            return Objects.hash(className);
        }
    }

    public static class MethodInfo {
        public String repoFullName;
        public String parentCommitSHA;
        public String commitSHA;
        public Path filePath;
        public int nArgs;
        public boolean hasVariableLengthArgument;
        public String methodName;
        public int lineStart, lineEnd;
        public String body;
        public boolean isConstructor = false;

        public MethodInfo(int _lineStart, int _lineEnd, String _methodName, int _nArgs, Path _filePath) {
            this.lineStart = _lineStart;
            this.lineEnd = _lineEnd;
            this.methodName = _methodName;
            this.nArgs = _nArgs;
            this.filePath = _filePath;
        }

        public void SetNArgsVariable(boolean hasVariableLengthArgument)
        {
            this.hasVariableLengthArgument = hasVariableLengthArgument;
        }

        public void SetConstructor(boolean isConstructor)
        {
            this.isConstructor = isConstructor;
        }


        @Override
        public boolean equals(Object o) {
            // NOTE
            // Keep comparison only name and argument.
            // If you add "path", the following line breaks:
            // `methodReplacements.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));`
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MethodInfo that = (MethodInfo) o;
            if(!methodName.equals(that.methodName))
                return false;

            // NO! because when we find method calls, we don't know if they are constructor, and we leave this boolean
            // false, which when later we compare with constructor declaration, the equality fails.
//            if(this.isConstructor!=that.isConstructor)
//                return false;

            if(this.hasVariableLengthArgument == false && that.hasVariableLengthArgument==false)
                return (nArgs == that.nArgs);
            else if(this.hasVariableLengthArgument ==true && that.hasVariableLengthArgument == true)
                return (nArgs == that.nArgs);
            else if(this.hasVariableLengthArgument)   // this=methodDecl, that=methodCall
                return (nArgs <= that.nArgs);
            else // if(that.nArgsVariable)
                return (nArgs >= that.nArgs);
        }

        @Override
        public int hashCode() {
            return Objects.hash(nArgs, methodName);
        }

        @Override
        public String toString() {
            if(!hasVariableLengthArgument)
                return String.format("%s(%d)", methodName, nArgs);
            else
                return String.format("%s(%d+)", methodName, nArgs);
        }
    }
}
