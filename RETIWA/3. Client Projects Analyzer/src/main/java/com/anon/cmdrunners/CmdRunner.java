package com.anon.cmdrunners;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;


/**
 * Always check for latest version of this class at: https://gist.github.com/emadpres/2334cc71e27ccc5055b062538c25f111
 *
 * Version: 6.1 (2021-11-05)
 * Change log:
 * - BUG FIX: Waiting for output consumer to read the whole output
 */

public class CmdRunner {

    private static final Logger logger = LoggerFactory.getLogger(CmdRunner.class);


    private static CmdRunner ourInstance = new CmdRunner();
    public final static int RETURN_CODE_SUCCESS = 0;
    public final static int RETURN_FAILED = -1;
    public final static int RETURN_TIMEOUT = -2;

    public static CmdRunner getInstance() {
        return ourInstance;
    }

    public int RunCommand_ReturnErrCode(List<String> cmd, Path workingDir)
    {
        return RunCommand_ReturnErrCode(cmd, workingDir, null, -1);
    }

    /**
     * @param workingDir    If `null`, it mean it doesn't matter
     * @param timeout_sec   pass -1 for no timeout
     */
    public int RunCommand_ReturnErrCode(List<String> cmd, Path workingDir, Map<String, String> environmentVariables, int timeout_sec)
    {
        CmdRunnerResult cmdRunnerResult = RunCommand(cmd, workingDir, environmentVariables, timeout_sec);
        return cmdRunnerResult.returnCode;
    }

    /**
     * @param workingDir    If `null`, it mean it doesn't matter
     * @param timeout_sec   pass -1 for no timeout
     */
    public String RunCommand_ReturnOutput(List<String> cmd, Path workingDir, Map<String, String> environmentVariables, int timeout_sec)
    {
        CmdRunnerResult cmdRunnerResult = RunCommand(cmd, workingDir, environmentVariables, timeout_sec);
        if(cmdRunnerResult.returnCode != 0)
            return null;
        else
            return cmdRunnerResult.output;
    }



    /**
     * Don't put the double quotes in. That's only used when writing a command-line in the shell!
     * echo "Hello, world!" ===> Runtime.getRuntime().exec(new String[] {"echo", "Hello, world!"});
     * @param workingDir    If `null`, it mean it doesn't matter
     * @param timeout_sec   pass -1 for no timeout
     */
    public CmdRunnerResult RunCommand(List<String> cmd, Path workingDir, Map<String, String> environmentVariables, int timeout_sec)
    {
        String[] cmd_array = cmd.toArray(new String[0]);
        InputStreamConsumerThread inputConsumer = null;
        InputStreamConsumerThread errorConsumer = null;
        int returnCode = RETURN_FAILED;
        try {

            ProcessBuilder pb = new ProcessBuilder(cmd_array);
            if(workingDir!=null)
                pb.directory(workingDir.toFile());

            if(environmentVariables!=null) {
                for (Map.Entry<String, String> pair : environmentVariables.entrySet()) {
                    pb.environment().put(pair.getKey(), pair.getValue());
                }
            }

            Process process = pb.start();

            inputConsumer = new InputStreamConsumerThread(process.getInputStream());
            errorConsumer = new InputStreamConsumerThread(process.getErrorStream());
            inputConsumer.start();
            errorConsumer.start();

            if(timeout_sec == -1) {
                returnCode = process.waitFor(); // This should be after reading output (aka readInputStream)
                inputConsumer.join();
                errorConsumer.join();
            }
            else
            {
                boolean noTimeout = process.waitFor(timeout_sec, TimeUnit.SECONDS);
                if(noTimeout) {
                    returnCode = process.exitValue();
                    inputConsumer.join(timeout_sec* 1000L);
                    errorConsumer.join(timeout_sec* 1000L);
                }
                else {
                    while(process.isAlive()) {
                        logger.error("Trying to kill timed-out process PID={} ...",process.pid());
                        Thread.sleep(1000);
                        process.destroyForcibly();
                    }
                    returnCode = RETURN_TIMEOUT;
                }
            }

        } catch (Exception e) {
            logger.error("Exception! ",e);
        }

        if(inputConsumer==null || errorConsumer == null)
            return new CmdRunnerResult(returnCode, null, null);
        else
            return new CmdRunnerResult(returnCode, inputConsumer.getOutput(), errorConsumer.getOutput());
    }


//    public String RunCommandAndReturnOutput(List<String> cmd, Path workingDir, long timeout_s)
//    {
//        String[] cmd_array = cmd.toArray(new String[0]);
//        Process process = null;
//        int returnCode = RETURN_FAILED;
//        try {
//
//            File tempOut = File.createTempFile("RunCommandAndReturn-", ".txt");
//
//            ProcessBuilder pb = new ProcessBuilder(cmd_array);
//            pb.redirectErrorStream(true); // Redirect stderr into stdout => we just read stdout
//            pb.directory(workingDir==null?null:workingDir.toFile());
//            pb.redirectOutput(tempOut);
//
//            process = pb.start();
//
//            if (!process.waitFor(timeout_s, TimeUnit.SECONDS)) {
//                logger.error("Destroy: {}", cmd);
//                process.destroyForcibly();
//                return null;
//            }
//            returnCode = process.exitValue();
//
//            String output = FileUtils.readFileToString(tempOut);
//            tempOut.delete();
//            return output;
//
//        } catch (Exception e) {
//            logger.error("Exception! ",e);
//        }
//
//        return null;
//    }

//    /**
//     * "Use the new improved method with `timeout` functionality"
//     */
//    @Deprecated
//    public String RunCommandAndReturnOutput(List<String> cmd, Path workingDir)
//    {
//        String[] cmd_array = cmd.toArray(new String[0]);
//        Process process = null;
//        int returnCode = RETURN_FAILED;
//        try {
//
//            ProcessBuilder pb = new ProcessBuilder(cmd_array);
//            pb.redirectErrorStream(true);  // Redirect stderr into stdout => we just read stdout
//            pb.directory(workingDir==null?null:workingDir.toFile());
//
//            process = pb.start();
//
//            InputStream is = process.getInputStream();
//            String output = ReadInputStream(is);
//            returnCode = process.waitFor();
//
//            return output;
//
//        } catch (Exception e) {
//            logger.error("Exception! ",e);
//        }
//
//        return null;
//    }


    public static class CmdRunnerResult {
        String output, err;
        int returnCode;

        public CmdRunnerResult(int returnCode, String output, String err) {
            this.returnCode = returnCode;
            this.output = output;
            this.err = err;
        }
    }

    private static class InputStreamConsumerThread extends Thread
    {
        private final InputStream is;
        private final StringBuilder output = new StringBuilder();

        public InputStreamConsumerThread (InputStream is)
        {
            this.is=is;
        }

        public void run()
        {
            /*
             *  Reading input is mandatory, otherwise we get stuck. See https://stackoverflow.com/questions/5483830
             *
             *  Note: In first implementation we used BufferedReader.readLine but due to ^M (=CR=\r) issue
             *  in git-diff output we were getting newline at ^M positions which broke expected git-diff
             *  output syntax. So we now read char-by-char and ignore CR characters.
             */
            try (final BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                int ch;
                while ((ch = reader.read()) != -1) {
                    if((char)ch=='\r')
                        continue;
                    output.append((char) ch);
                }
            } catch (final Exception e) {
                logger.error("Exception while reading CmdRunner streams ",e);
            }

//            try(BufferedReader br = new BufferedReader(new InputStreamReader(is)))
//            {
//                for (String line = br.readLine(); line != null; line = br.readLine())
//                {
//                    output.append(line).append("\n");
//                }
//            } catch (Exception e) {
//                logger.error("Exception while reading CmdRunner streams ",e);
//            }
        }
        public String getOutput(){
            return output.toString();
        }
    }
}

