package com.anon.cmdrunners;

import com.anon.datatype.CommitInfo;
import com.anon.datatype.ImportStatementChanges;
import com.anon.datatype.RepositoryInfo;
import com.anon.helpers.IO;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Always check for latest version of this class at: https://gist.github.com/emadpres/2334cc71e27ccc5055b062538c25f111
 *
 * Version: 2.1 (2021-11-05)
 * Change log:
 * - BUG FIX: Getting list of changed files if path include space
 * - Adding 120sec timeout
 *
 * NOTE: This class relies on `CmdRunner` (available at: https://gist.github.com/emadpres/2334cc71e27ccc5055b062538c25f111)
 */
public class GitCmdRunner {
    private static final Logger logger = LoggerFactory.getLogger(GitCmdRunner.class);
    final static public String EMPTY_TREE_SHA = "4b825dc642cb6eb9a060e54bf8d69288fbee4904";
    final static public int TIMEOUT_SEC = 120;

    // *************************************************************************
    // *********************** git-log operations ******************************
    // *************************************************************************

    public static List<String> git_log(Path repoPath) {
        List<String> command = List.of("git", "log", "--all", "--no-merges", "--pretty=format:%H");

        //String output = CmdRunner.getInstance().RunCommandAndReturnOutput(cmd, repoPath);
        String output = CmdRunner.getInstance().RunCommand_ReturnOutput(command, repoPath,null, TIMEOUT_SEC);
        if(output==null)
            return null;
        List<String> allCommits = Arrays.asList(output.split("\n"));
        return allCommits;
    }

    public static List<CommitInfo> GetListOfCommits_withMessage_FirstParent(RepositoryInfo _repo, boolean withCommitMessage, boolean insertEmptyTreeSHA) {
        // TODO: Check a parent commit not having a more recent date
        String pretty_format = "--pretty=format:%H";
        if(withCommitMessage)
            pretty_format = "--pretty=format:%H,%s";

        List<String> command = List.of("git", "log", _repo.GetDefaultBranch(), "--first-parent", pretty_format);

        //String output = CmdRunner.getInstance().RunCommandAndReturnOutput(command, _repo.GetPath());
        String output = CmdRunner.getInstance().RunCommand_ReturnOutput(command,_repo.GetPath(),null, TIMEOUT_SEC);
        if(output==null)
            return null;

        List<CommitInfo> allCommits = new ArrayList<>();
        for (String line : output.split("\n")) {
            if(withCommitMessage) {
                String[] split = line.split(",");
                allCommits.add(new CommitInfo(split[0], split.length == 1 ? "" : split[1])); // check for cases when msg is empty
            }
            else
                allCommits.add(new CommitInfo(line)); // check for cases when msg is empty
        }

        if(insertEmptyTreeSHA)
            allCommits.add(new CommitInfo(EMPTY_TREE_SHA,""));
        return allCommits;
    }



    // *************************************************************************
    // *************************** Others **************************************
    // *************************************************************************

    public static boolean CheckoutCommit(Path repoPath, String commitSHA) {
        List<String> command = List.of("bash", "-c", String.format("git reset --hard; git clean -f -d; git checkout -f %s", commitSHA));

        int res = CmdRunner.getInstance().RunCommand_ReturnErrCode(command, repoPath);
        return (res == CmdRunner.RETURN_CODE_SUCCESS);
    }

    public static String GetFileContentAtSpecificCommit(Path repoPath, String commitSHA, Path filepath) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("show");
        command.add(String.format("%s:%s", commitSHA, filepath.toString()));

        String output = CmdRunner.getInstance().RunCommand_ReturnOutput(command, repoPath,null, TIMEOUT_SEC);
        return output;
    }

    /**
     *  List (ls) all files at specific commits
     * @param repoPath
     * @param commitSHA
     * @param specificExtension will be ignored if null.
     * @return
     */
    public static List<String> ListFilesAtSpecificCommit(Path repoPath, String commitSHA, String specificExtension) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("ls-tree");
        command.add("-r");
        command.add("--name-only");
        command.add("--full-name");
        command.add(commitSHA);
        String output = CmdRunner.getInstance().RunCommand_ReturnOutput(command, repoPath,null, TIMEOUT_SEC);

        List<String> files = new ArrayList<>();
        for (String line : output.split("\n")) {
            if(specificExtension==null)
                files.add(line);
            else if(line.endsWith(specificExtension))
                files.add(line);
        }

        return files;
    }

    /**
     * Don't forget to delete the file you yourself.
     */
    public static Path GetFileContentAtSpecificCommitAndSaveOnTempFile(Path repoPath, String commitSHA, Path filepath) {
        String content = GetFileContentAtSpecificCommit(repoPath, commitSHA, filepath);
        if(content==null)
            return null;
        Path tempPath = IO.WriteStringOnTempFile(content,"srcml-", ".code");
        return tempPath;
    }


    /**
     * Check if a repo at given url is publicly available.
     * @param repo_http_url The http url of the repo. Technically the same code should also work for ssh url, but in my
     *                      tests, ssh prompts (for adding fingerprint, whatsoever) which kills the command as we disabled
     *                      prompts. (Prompts should remain disabled, otherwise GitHub asks user/pass for private repos)
     */
    public static boolean CheckIfRepoExists(String repo_http_url)
    {
        Map<String, String> env = new HashMap<>() {{this.put("GIT_TERMINAL_PROMPT", "0");}};
        List<String> command = List.of("git", "ls-remote", repo_http_url);


        int res = CmdRunner.getInstance().RunCommand_ReturnErrCode(command, null, env, TIMEOUT_SEC);
        return (res == 0);
    }

    // *************************************************************************
    // ********************* git-diff operations *******************************
    // *************************************************************************


    /**
     * Using `git diff`, returns list of "Modified" "Java" files
     * (NOT added, deleted, ...)
     */
    @Deprecated
    public static List<Path> git_diff_GetModifiedJavFiles(Path repoPath, String beforeCommit, String afterCommit) {
        List<String> command = new ArrayList<>();
        // git diff --ignore-submodules --ignore-all-space --diff-filter=M --name-only 29da3bb863ffbf2a471 691bac395ecb300 -- '*.java'
        command.add("git");
        command.add("diff");
        command.add("--no-color");
        command.add("--ignore-submodules");
        command.add("--ignore-all-space");
        command.add("--diff-filter=M");
        command.add("--name-only");
        command.add(beforeCommit);
        command.add(afterCommit);
        command.add("--");
        command.add("*.java");

        String output = CmdRunner.getInstance().RunCommand_ReturnOutput(command, repoPath,null, TIMEOUT_SEC);
        if(output==null)
            return null;

        List<Path> results = new ArrayList<>();
        for(String s: output.split("\n"))
            results.add(Path.of(s));
        return results;
    }


    /**
     * Return list of changed (Added,Deleted,Modified,...) files
     * @param  onlySpecificExtension   For receiving only "java"/C++ changes, pass "java"/"cpp" (NOT ".cpp", "*.cpp")
     */
    public static FilesStatus git_diff_GetFilesStatus(Path repoPath, String beforeCommit, String afterCommit,
                                                      String onlySpecificExtension, boolean ignoreWhiteSpace) {
        List<String> command = new ArrayList<>();
        // git diff --ignore-submodules --ignore-all-space --diff-filter=M --name-only 29da3bb863ffbf2a471 691bac395ecb300 -- '*.java'
        command.add("git");
        command.add("diff");
        command.add("--no-color");
        if(ignoreWhiteSpace) {
            command.add("--ignore-submodules");
            command.add("--ignore-all-space");
        }
        command.add("--name-status");
        command.add(beforeCommit);
        command.add(afterCommit);
        if(onlySpecificExtension != null && !onlySpecificExtension.isEmpty()) {
            command.add("--");
            command.add("*."+onlySpecificExtension);
        }

        String output = CmdRunner.getInstance().RunCommand_ReturnOutput(command, repoPath,null, TIMEOUT_SEC);
        if(output==null || output.isEmpty())
            return new FilesStatus();

        FilesStatus result = new FilesStatus();

        for(String s: output.split("\n")) {
            // each line is like: "A    path/to/affected/file.java  path/to/renamed/file.java"
            String[] split = s.split("\t");
            String action = split[0];

            // We might see such warnings at the end of diff result:
            //  - "warning: inexact rename detection was skipped due to too many files."
            //  - "warning: you may want to set your diff.renameLimit variable to at least 1239 and retry the command."
            if(action.startsWith("warning"))
                continue;

            result.totalCount++;
            if(action.equals("A"))
                result.added.add(Path.of(split[1]));
            else if(action.equals("D"))
                result.deleted.add(Path.of(split[1]));
            else if(action.equals("M"))
                result.modified.add(Path.of(split[1]));
            else if(action.startsWith("R")) {
                Path oldPath = Path.of(split[1]);
                Path newPath = Path.of(split[2]);
                result.renamed_from_to.add(new GitFilePath( oldPath, newPath));
            }
            else if(action.startsWith("C"))
                result.added.add(Path.of(split[2])); // we consider the copied file (third element in split) as a new file
            else
                result.other.add(Path.of(split[1]));
        }
        return result;
    }

    /**
     * Return the `git diff` output for a specific file
     * @param filePath_old  Useful when file is renamed, otherwise pass either `null` or the same filePath_new value
     */
    public static String git_diff(Path repoPath, String beforeCommit, String afterCommit, Path filePath_old, Path filePath_new, boolean wordDiff, boolean ignoreWhiteSpace) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("diff");
        command.add("--no-color");
        command.add("--unified=0");
        if(wordDiff)
            command.add("--word-diff");
        if(ignoreWhiteSpace) {
            command.add("--ignore-submodules");
            command.add("--ignore-all-space");
        }
        command.add(beforeCommit);
        command.add(afterCommit);
        command.add("--");
        if(filePath_old!=null && filePath_old.equals(filePath_new)==false)
            command.add(String.valueOf(filePath_old));
        command.add(filePath_new.toString());

        String output = CmdRunner.getInstance().RunCommand_ReturnOutput(command, repoPath,null, TIMEOUT_SEC);
        if(output==null)
            return "";
        return output;
    }

    /**
     * Using `git diff` output, this method returns pairs of changes.
     * How it works?
     *      How output list look like? Consider diff below:
     *          > before: if(line.getWords().size() > 0) {
     *          > after:  if(line.words().size() > 0) {
     *      which with `git diff --word-diff` look like:
     *          > if[-(line.getWords().size()-]{+(line.words().size()+} > 0) {
     *      where text changes are marked as "blah blah [-XXX-]{+YYY+} blah [-ZZZ-] blah".
     *      This methods return a list of (XXX,YYY) and (ZZZ,null) for the example above
     *
     * @param filePath_old  Useful when file is renamed, otherwise pass either `null` or the same filePath_new value
     */
    public static List<CodeReplacement> git_diff_JustChanges(boolean onlyPairs, Path repoPath, String beforeCommit, String afterCommit, Path filePath_old, Path filePath_new, boolean ignoreWhiteSpace)
    {
        String WordDiffCode = git_diff(repoPath, beforeCommit, afterCommit, filePath_old, filePath_new,true,ignoreWhiteSpace);

        List<CodeReplacement> res = new ArrayList<>();

        String GIT_DIFF__RM_PATTERN = "\\[-((?:(?!\\[-).)*?)-\\]"; // See https://stackoverflow.com/questions/70386440
        String GIT_DIFF__ADD_PATTERN = "\\{\\+((?:(?!\\{\\+).)*?)\\+\\}";

        Pattern line_numbers_pattern = Pattern.compile("@@ \\-(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");
        Pattern rm_add_pattern = Pattern.compile(String.format("%s%s", GIT_DIFF__RM_PATTERN, GIT_DIFF__ADD_PATTERN));
        Pattern rm_pattern = Pattern.compile(String.format("%s(?!\\{)", GIT_DIFF__RM_PATTERN));
        Pattern add_pattern = Pattern.compile(String.format("(?<!\\])%s", GIT_DIFF__ADD_PATTERN));

        int before_startLine=-1, after_startLine=-1;
        boolean foundHunkHeader = false;
        for (String line : WordDiffCode.split("\n")) {
            line = line.strip();
            if(line.startsWith("diff --git") || line.startsWith("---") || line.startsWith("+++")) {
                foundHunkHeader =  false; // now we start ignoring header lines until we get to hunks part
                continue;
            }

            if(line.startsWith("@@"))
            {
                // range information part
                Matcher line_matcher = line_numbers_pattern.matcher(line);
                if(line_matcher.find())
                {
                    before_startLine = Integer.parseInt(line_matcher.group(1));
                    after_startLine = Integer.parseInt(line_matcher.group(2));
                    foundHunkHeader =  true;
                }
                else
                {
                    logger.error("Failed extract line numbers from git diff chunk: {}", line);
                }
            }
            else if(foundHunkHeader)
            {
                // code diff part

                if(before_startLine==-1 || after_startLine==-1) {
                    logger.error("Line information is missing");
                    continue;
                }

                if(line.strip().isEmpty()) // performance-wise I added this common special case to avoid below matchers
                {
                    before_startLine++;
                    after_startLine++;
                }
                else {
                    Matcher regex_rm_add_pair = rm_add_pattern.matcher(line); // [-XXX-]{+XXX+}

                    while (true) {
                        boolean success = regex_rm_add_pair.find();
                        if (!success) break;
                        String removedText = regex_rm_add_pair.group(1);
                        String addedText = regex_rm_add_pair.group(2);
                        res.add(new CodeReplacement(removedText, addedText, before_startLine, after_startLine));
                    }

                    if (onlyPairs == false) {
                        Matcher regex_rm = rm_pattern.matcher(line); // [-XXX-] and no { after it
                        Matcher regex_add = add_pattern.matcher(line); // {+XXX+} and no ] before it

                        while (true) {
                            boolean success = regex_rm.find();
                            if (!success) break;
                            String removedText = regex_rm.group(1);
                            res.add(new CodeReplacement(removedText, null, before_startLine, after_startLine));
                        }

                        while (true) {
                            boolean success = regex_add.find();
                            if (!success) break;
                            String addedText = regex_add.group(1);
                            res.add(new CodeReplacement(null, addedText, before_startLine, after_startLine));
                        }
                    }

                    if (line.startsWith("{+") && line.endsWith("+}"))
                        after_startLine++;
                    else if (line.startsWith("[-") && line.endsWith("-]"))
                        before_startLine++;
                    else {
                        before_startLine++;
                        after_startLine++;
                    }
                }

            }
        }

        return res;
    }


    /**
     * Using `git diff`, return added/removed import lines.
     * @param filePath_old  Useful when file is renamed, otherwise pass either `null` or the same filePath_new value
     */
    public static ImportStatementChanges git_diff_JustImportsChanges(Path repoPath, String beforeCommit, String afterCommit, Path filePath_old, Path filePath_new, boolean ignoreWhiteSpace) {
        String output = git_diff(repoPath, beforeCommit,afterCommit, filePath_old, filePath_new, false, ignoreWhiteSpace);

        ImportStatementChanges res = new ImportStatementChanges(filePath_new);
        if(output==null)
            return res;

        Matcher regex_import_added = Pattern.compile("\\+import (.*)").matcher(output);
        Matcher regex_import_removed = Pattern.compile("-import (.*)").matcher(output);
        while(true)
        {
            boolean success = regex_import_added.find();
            if(!success) break;
            String text = regex_import_added.group(1);
            res.AddAAddedImport(text);
        }
        while(true)
        {
            boolean success = regex_import_removed.find();
            if(!success) break;
            String text = regex_import_removed.group(1);
            res.AddARemovedImport(text);
        }

        return res;
    }


    /**
     * Using `git diff`, returns diff hunks.
     * @param filePath_old  Useful when file is renamed, otherwise pass either `null` or the same filePath_new value
     */
    private static List<DiffChunk> git_diff_GetHunks(Path repoPath, String beforeCommit, String afterCommit, Path filePath_old, Path filePath_new, boolean wordDiff, boolean ignoreWhiteSpace)
    {
        String diffOutput = git_diff(repoPath, beforeCommit, afterCommit, filePath_old, filePath_new, wordDiff, ignoreWhiteSpace);

        List<DiffChunk> res = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        int currentChunkStartLine=-1, currentChunkEndLine=-1;

        String[] lines = diffOutput.split("\n");



        for(String line: lines)
        {
            if(!line.startsWith("@@")) {
                currentChunk.append(line);
                currentChunk.append('\n');
            }
            else {
                // we reached a new chunk
                if(currentChunk.length()>0)
                {
                    res.add(new DiffChunk(currentChunk.toString(), currentChunkStartLine, currentChunkEndLine));
                    currentChunk = new StringBuilder();
                }

                line = line.substring(3, line.indexOf("@@",2)-1);
                String[] split = line.split(" ");
                String newFileLineRange = split[1];
                int commaIndex = newFileLineRange.indexOf(',');

                if(commaIndex==-1)
                {
                    currentChunkStartLine = Integer.parseInt(newFileLineRange);
                    currentChunkEndLine = currentChunkStartLine;
                }
                else
                {
                    currentChunkStartLine = Integer.parseInt(newFileLineRange.substring(0, commaIndex));
                    currentChunkEndLine = currentChunkStartLine+Integer.parseInt(newFileLineRange.substring(commaIndex+1))-1;
                }

            }
        }

        return res;
    }


    /**
     * Using `git diff`, returns line-ranges of changed parts.
     * NOTE!!! This method is refactored but new implementation is never tested!
     * @param filePath_old  Useful when file is renamed, otherwise pass either `null` or the same filePath_new value
     */
    public static List<ImmutablePair<Integer, Integer>> git_diff_ExtractModifiedLines(Path repoPath, String commitBefore, String commitAfter, Path filePath_old, Path filePath_new, boolean ignoreWhiteSpace) {
        String output = git_diff(repoPath, commitBefore, commitAfter, filePath_old, filePath_new,false, ignoreWhiteSpace);

        Pattern line_numbers_pattern = Pattern.compile("@@ \\-(\\d+)(,\\d+)? \\+(\\d+)(,\\d+)? @@");

        List<ImmutablePair<Integer, Integer>> ranges = new ArrayList<>();
        for(String line: output.split("\n"))
        {
            if(line.startsWith("@@")==false) continue;

            Matcher line_matcher = line_numbers_pattern.matcher(line);
            if(line_matcher.find())
            {
                int before_startLine = Integer.parseInt(line_matcher.group(1));
                int before_nLines = 1;
                String g2 = line_matcher.group(2);
                if(g2 != null)
                    before_nLines = Integer.parseInt(g2);

                int after_startLine = Integer.parseInt(line_matcher.group(3));
                int after_nLines = 1;
                String g4 = line_matcher.group(4);
                if(g4 != null)
                    after_nLines = Integer.parseInt(g4);
                ranges.add(ImmutablePair.of(after_startLine, after_startLine+after_nLines-1));
            } else
            {
                logger.error("Failed extract line numbers from git diff chunk: {}", line);
            }


//            line = line.substring(3, line.indexOf("@@",2)-1);
//            String[] split = line.split(" ");
//            String newFileLineRange = split[1];
//            int commaIndex = newFileLineRange.indexOf(',');
//            int start,end;
//            if(commaIndex==-1)
//            {
//                start = Integer.parseInt(newFileLineRange);
//                end = start;
//            }
//            else
//            {
//                start = Integer.parseInt(newFileLineRange.substring(0, commaIndex));
//                end = start+Integer.parseInt(newFileLineRange.substring(commaIndex+1))-1;
//            }
        }
        return ranges;
    }


    public static class CodeReplacement {
        public int lineStart_before, lineStart_after;
        public String removedCode, addedCode;

        public CodeReplacement(String _removedCode, String _addedCode, int _lineStart_before, int _lineStart_after) {
            this.removedCode = _removedCode.trim();
            this.addedCode = _addedCode.trim();
            this.lineStart_before = _lineStart_before;
            this.lineStart_after = _lineStart_after;
        }
    }

    public static class DiffChunk {
        public String code;
        public int start, end;

        public DiffChunk(String code, int start, int end) {
            this.code = code;
            this.start = start;
            this.end = end;
        }
    }

    public static class FilesStatus {
        /**
         * Added (A), Copied (C), Deleted (D), Modified (M), Renamed (R),
         * have their type (i.e. regular file, symlink, submodule, ...) changed (T),
         * are Unmerged (U), are Unknown (X), or have had their pairing Broken (B).
         * source: https://git-scm.com/docs/git-diff#Documentation/git-diff.txt---diff-filterACDMRTUXB82308203
         */
        public int totalCount = 0;  // everything (added, deleted, renamed, modified, copied, ...)
        public List<Path> added = new ArrayList<>();
        public List<Path> deleted = new ArrayList<>();
        public List<Path> modified = new ArrayList<>();
        public List<GitFilePath> renamed_from_to = new ArrayList<>();
        public List<Path> other = new ArrayList<>();

        @Override
        public String toString() {
            return "totalCount=" + totalCount +
                    "\n\tA=" + added +
                    "\n\tD=" + deleted +
                    "\n\tM=" + modified +
                    "\n\tR=" + renamed_from_to +
                    "\n\tother=" + other + "\n";
        }
    }

    /**
     * Like Path, but with before/after to handle renaming situations
     */
    public static class GitFilePath {
        public Path filePath;
        public Path oldFilePath; // if no remaining, oldFilePath=filePath

        public GitFilePath(Path _oldFilePathBeforeRenaming, Path _filePath) {
            this.oldFilePath = _oldFilePathBeforeRenaming;
            this.filePath = _filePath;
        }

        public GitFilePath(Path _filePath) {
            this.filePath = _filePath;
            this.oldFilePath = _filePath;
        }

        @Override
        public String toString() {
            return filePath.toString();
        }
    }
}
