package org.sugarj.common;

import static org.sugarj.common.Log.log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.sugarj.common.path.Path;


/**
 * Provides methods for calling external commands. Includes input
 * and output stream forwarding to standard in and out.
 * 
 * @author Sebastian Erdweg <seba at informatik uni-marburg de>
 */
public class CommandExecution {

  private static ExecutorService ioThreadPool = Executors.newCachedThreadPool();
  
  /**
   * silences the main process
   */
  public static boolean SILENT_EXECUTION = false;

  /**
   * silences the sub processes
   */
  public static boolean SUB_SILENT_EXECUTION = true;

  /**
   * displays full command lines
   */
  public static boolean FULL_COMMAND_LINE = false;

  /**
   * Line-wraps command lines (if displayed at all)
   */
  public final static boolean WRAP_COMMAND_LINE = true;

  /**
   * displays caching information
   */
  public static boolean CACHE_INFO = true;


  public static class ExecutionError extends Error {
    private static final long serialVersionUID = -4924660269220590175L;
    private final String[] cmds;
    private final String[] outMsgs;
    private final String[] errMsgs;

    public ExecutionError(String message, String[] cmds, String[] outMsgs, String[] errMsgs) {
      super(message + ": " + log.commandLineAsString(cmds));
      this.cmds = cmds;
      this.outMsgs = outMsgs;
      this.errMsgs = errMsgs;
    }
    
    public ExecutionError(String message, String[] cmds, String[] outMsgs, String[] errMsgs, Throwable cause) {
      super(message + ": " + log.commandLineAsString(cmds), cause);
      this.cmds = cmds;
      this.outMsgs = outMsgs;
      this.errMsgs = errMsgs;
    }

    public String[] getCmds() {
      return cmds;
    }

    public String[] getOutMsgs() {
      return outMsgs;
    }

    public String[] getErrMsgs() {
      return errMsgs;
    }
  }
  
  
  
  private boolean silent;
  
  public CommandExecution(boolean silent) {
    this.silent = silent;
  }
  
  /**
   * A thread that forwards the stream in to the stream out,
   * prepending a prefix to each line. See
   * http://www.javaworld.com
   * /javaworld/jw-12-2000/jw-1229-traps.html to understand why
   * we need this.
   */
  private class StreamRunner implements Callable<List<String>> {
    private final InputStream in;
    private String prefix;

    private List<String> msg = new ArrayList<String>();
    
    public StreamRunner(InputStream in, String prefix) {
      this.in = in;
      this.prefix = prefix;
    }

    @Override
    public List<String> call() {
      try {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        String line = null;
        while ((line = reader.readLine()) != null) {
          msg.add(prefix + line);
          if (!silent)
            log.logErr(prefix + line, Log.ALWAYS);
        }
      } catch (IOException ioe) {
        ioe.printStackTrace();
      }
      return msg;
    }
    
    public synchronized List<String> peek() {
      return msg;
    }
  }


  /**
   * Executes the given command.
   * <p>
   * All paths given to this function have to be treated by
   * {@link FileCommands#toCygwinPath} (if a cygwin program is to
   * be executed) or {@link FileCommands#toWindowsPath} (if a
   * native Windows program is to be executed) as appropriate.
   * <p>
   * The first argument of this method is used as a short name of
   * the command for logging purposes.
   * 
   * @param cmds
   *        the executable and its argument to execute
   * @throws IOException
   *         when something goes wrong
   */
  public String[][] execute(Path dir, String... cmds) {
    return executeWithPrefix(cmds[0], dir, cmds);
  }
  public String[][] execute(String... cmds) {
    return executeWithPrefix(cmds[0], null, cmds);
  }
  

  /**
   * Executes the given command.
   * <p>
   * All paths given to this function have to be treated by
   * {@link FileCommands#toCygwinPath} (if a cygwin program is to
   * be executed) or {@link FileCommands#toWindowsPath} (if a
   * native Windows program is to be executed) as appropriate.
   * 
   * @param prefix
   *        a short version of the command for logging purposes
   * @param cmds
   *        the executable and its argument to execute
   * @throws IOException
   *         when something goes wrong
   */
  public String[][] executeWithPrefix(String prefix, String... cmds) {
    return executeWithPrefix(prefix, null, cmds);
  }
  public String[][] executeWithPrefix(String prefix, Path dir, String... cmds) {
    int exitValue;
    
    StreamRunner errStreamLogger = null;
    StreamRunner outStreamLogger = null;
    try {
      Runtime rt = Runtime.getRuntime();

//      if (!SILENT_EXECUTION) {
//        log.beginExecution(prefix, cmds);
//      }

      Process p = rt.exec(cmds, null, dir == null ? null : dir.getFile());

      errStreamLogger = new StreamRunner(p.getErrorStream(), "");
      outStreamLogger = new StreamRunner(p.getInputStream(), "");

      // We need to start these threads even if we don't care for
      // the output, because the process will block if we don't
      // read from the streams

      Future<List<String>> outFuture = ioThreadPool.submit(outStreamLogger);
      Future<List<String>> errFuture = ioThreadPool.submit(errStreamLogger);

      // Wait for the process to finish
      exitValue = p.waitFor();
      List<String> outMsgs = outFuture.get();
      List<String> errMsgs = errFuture.get();

      if (exitValue != 0) {
        throw new ExecutionError("Command failed", cmds, outMsgs.toArray(new String[outMsgs.size()]), errMsgs.toArray(new String[errMsgs.size()]));
      }
      
      return new String[][]{outMsgs.toArray(new String[outMsgs.size()]), errMsgs.toArray(new String[errMsgs.size()])};
    } catch (ExecutionError e) {
      throw e; 
    } catch (Throwable t) {
      List<String> outMsgs = outStreamLogger == null ? new ArrayList<String>() : outStreamLogger.peek();
      List<String> errMsgs = errStreamLogger == null ? new ArrayList<String>() : errStreamLogger.peek();

      throw new ExecutionError("problems while executing " + prefix + ": " + t.getMessage(), cmds, outMsgs.toArray(new String[outMsgs.size()]), errMsgs.toArray(new String[errMsgs.size()]), t);
    }
    
  }
}
