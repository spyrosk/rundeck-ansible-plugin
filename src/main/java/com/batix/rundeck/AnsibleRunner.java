package com.batix.rundeck;

import com.dtolabs.rundeck.core.common.INodeSet;
import com.dtolabs.rundeck.core.utils.OptsUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class AnsibleRunner {
  enum AnsibleCommand {
    AdHoc("ansible"),
    Playbook("ansible-playbook");

    final String command;
    AnsibleCommand(String command) {
      this.command = command;
    }
  }

  public static AnsibleRunner adHoc(String module, String arg) {
    AnsibleRunner ar = new AnsibleRunner(AnsibleCommand.AdHoc);
    ar.module = module;
    ar.arg = arg;
    return ar;
  }

  public static AnsibleRunner playbook(String playbook) {
    AnsibleRunner ar = new AnsibleRunner(AnsibleCommand.Playbook);
    ar.playbook = playbook;
    return ar;
  }

  private static final Pattern pHostPlaybook = Pattern.compile(
    "^(.+?): \\[(.+?)\\] => (\\{.+?\\})$"
  );
  private static final Pattern pTask = Pattern.compile(
    "^TASK \\[(.+?)\\].*$"
  );

  private boolean done = false;
  private String output;
  private final List<AnsibleTask> results = new ArrayList<>();

  private final AnsibleCommand type;
  private String module;
  private String arg;
  private String[] extraArgs;
  private String playbook;
  private boolean debug;
  private Path tempDirectory;
  private boolean retainTempDirectory;
  private final List<String> limits = new ArrayList<>();
  private int result;

  private AnsibleRunner(AnsibleCommand type) {
    this.type = type;
  }

  public AnsibleRunner limit(String host) {
    limits.add(host);
    return this;
  }

  public AnsibleRunner limit(INodeSet nodes) {
    limits.addAll(nodes.getNodeNames());
    return this;
  }

  /**
   * Additional arguments to pass to the process
   */
  public AnsibleRunner extraArgs(String args) {
    if (args != null && args.length() > 0) {
      extraArgs = OptsUtil.burst(args);
    }
    return this;
  }

  /**
   * Additional arguments to pass to the process
   */
  public AnsibleRunner extraArgs(String[] args) {
    if (args != null && args.length > 0) {
      extraArgs = args;
    }
    return this;
  }

  /**
   * Additional arguments to pass to the process
   */
  public AnsibleRunner extraArgs(List<String> args) {
    if (args != null && args.size() > 0) {
      extraArgs = args.toArray(new String[] {});
    }
    return this;
  }

  /**
   * Run Ansible with -vvvv and print the command and output to the console / log
   */
  public AnsibleRunner debug() {
    return debug(true);
  }

  /**
   * Run Ansible with -vvvv and print the command and output to the console / log
   */
  public AnsibleRunner debug(boolean debug) {
    this.debug = debug;
    return this;
  }

  /**
   * Keep the temp dir around, dont' delete it.
   */
  public AnsibleRunner retainTempDirectory() {
    return retainTempDirectory(true);
  }

  /**
   * Keep the temp dir around, dont' delete it.
   */
  public AnsibleRunner retainTempDirectory(boolean retainTempDirectory) {
    this.retainTempDirectory = retainTempDirectory;
    return this;
  }

  /**
   * Specify in which directory Ansible is run.
   * If none is specified, a temporary directory will be created automatically.
   */
  public AnsibleRunner tempDirectory(Path dir) {
    if (dir != null) {
      this.tempDirectory = dir;
    }
    return this;
  }

  public int run() throws Exception {
    if (done) {
      throw new IllegalStateException("already done");
    }
    done = true;

    if (tempDirectory == null) {
      tempDirectory = Files.createTempDirectory("ansible-rundeck");
    }
    File outputFile = File.createTempFile("ansible-runner", "output");
    File tempFile = null;

    List<String> procArgs = new ArrayList<>();
    procArgs.add(type.command);

    if (type == AnsibleCommand.AdHoc) {
      procArgs.add("all");

      procArgs.add("-m");
      procArgs.add(module);

      if (arg != null && arg.length() > 0) {
        procArgs.add("-a");
        procArgs.add(arg);
      }
      procArgs.add("-t");
      procArgs.add(tempDirectory.toFile().getAbsolutePath());
    } else if (type == AnsibleCommand.Playbook) {
      procArgs.add(playbook);

      procArgs.add("-v"); // to get JSON output, one line per host and task
    }

    if (limits.size() == 1) {
      procArgs.add("-l");
      procArgs.add(limits.get(0));
    } else if (limits.size() > 1) {
      tempFile = File.createTempFile("ansible-runner", "targets");
      StringBuilder sb = new StringBuilder();
      for (String limit : limits) {
        sb.append(limit).append("\n");
      }
      Files.write(tempFile.toPath(), sb.toString().getBytes());

      procArgs.add("-l");
      procArgs.add("@" + tempFile.getAbsolutePath());
    }

    if (debug) {
      procArgs.add("-vvvv");
    }

    if (extraArgs != null && extraArgs.length > 0) {
      // split the extraArgs and add them as single args (might contain unquoted spaces which separate args)
      for (String extraArg : extraArgs) {
        Collections.addAll(procArgs, OptsUtil.burst(extraArg));
      }
    }

    if (debug) {
      System.out.println("Ansible command:\n" + procArgs);
    }

    Process proc = new ProcessBuilder()
      .command(procArgs)
      .directory(tempDirectory.toFile())
      .redirectErrorStream(true)
      .redirectOutput(outputFile) // redirect to file, stream might block on too much output
      .start();
    proc.waitFor();
    result = proc.exitValue();

    output = new String(Files.readAllBytes(outputFile.toPath()));
    if (!outputFile.delete()) {
      outputFile.deleteOnExit();
    }

    if (debug) {
      System.out.println("Ansible output:\n" + output);
    }

    if (type == AnsibleCommand.AdHoc) {
      results.add(parseTreeDir(tempDirectory));
    } else {
      parseOutput();
    }

    if (tempFile != null && !tempFile.delete()) {
      tempFile.deleteOnExit();
    }
    if (tempDirectory != null && !retainTempDirectory) {
      Files.walkFileTree(tempDirectory, new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          Files.delete(file);
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
          Files.delete(dir);
          return FileVisitResult.CONTINUE;
        }
      });
    }

    return result;
  }

  private AnsibleTask parseTreeDir(Path dir) throws IOException {
    final AnsibleTask task = new AnsibleTask();

    Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        AnsibleTaskResult result = new AnsibleTaskResult();
        task.results.add(result);

        result.host = file.toFile().getName();
        FileReader reader = new FileReader(file.toFile());
        result.json = new JsonParser().parse(reader).getAsJsonObject();
        reader.close();

        return FileVisitResult.CONTINUE;
      }
    });

    return task;
  }

  private void parseOutput() {
    if (type == AnsibleCommand.Playbook) {
      AnsibleTask curTask = null;

      for (String line : output.split("\\r?\\n")) {
        line = line.trim();

        Matcher mTask = pTask.matcher(line);
        if (mTask.find()) {
          curTask = new AnsibleTask();
          results.add(curTask);
          curTask.name = mTask.group(1);
          continue;
        }

        if (curTask == null) continue;

        Matcher mHost = pHostPlaybook.matcher(line);
        if (mHost.find()) {
          AnsibleTaskResult taskResult = new AnsibleTaskResult();
          curTask.results.add(taskResult);

          taskResult.result = mHost.group(1);
          taskResult.host = mHost.group(2);
          String jsonStr = mHost.group(3);
          taskResult.json = new JsonParser().parse(jsonStr).getAsJsonObject();
        }
      }
    }
  }

  public int getResult() {
    return result;
  }

  public String getOutput() {
    return output;
  }

  public List<AnsibleTask> getResults() {
    return Collections.unmodifiableList(results);
  }

  public static class AnsibleTask {
    String name;
    final List<AnsibleTaskResult> results = new ArrayList<>();
  }

  public static class AnsibleTaskResult {
    String host;
    String result;
    JsonObject json;
  }
}
