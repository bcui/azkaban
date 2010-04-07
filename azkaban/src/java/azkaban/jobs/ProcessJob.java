/*
 * Copyright 2010 LinkedIn, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package azkaban.jobs;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Level;

import azkaban.app.JobDescriptor;
import azkaban.common.jobs.AbstractJob;
import azkaban.common.jobs.Job;
import azkaban.common.utils.Props;

/**
 * A job that runs a simple unix command
 * 
 * @author jkreps
 * 
 */
public class ProcessJob extends AbstractJob implements Job {

	public static final String ENV_PREFIX = "env.";
	public static final String COMMAND = "command";
	public static final String WORKING_DIR = "working.dir";
	public static final String JOB_PROP_ENV = "JOB_PROP_FILE";
    public static final String JOB_NAME_ENV = "JOB_NAME";
    
	private final Props _props;
	private final String _jobPath;
	private final String _name;
	private final JobDescriptor _descriptor;
	private volatile Process _process;
	private volatile boolean _isComplete;

	public ProcessJob(JobDescriptor descriptor) {
		super(descriptor.getId());
		this._props = descriptor.getProps();
		this._isComplete = false;
		this._jobPath = descriptor.getFullPath();
		this._name = descriptor.getId();
		this._descriptor = descriptor;
	}

	public void run() {
		// Sets a list of all the commands that need to be run.
		List<String> commands = getCommandList();
		info(commands.size() + " commands to execute.");
        	
		Map<String, String> env = getEnvironmentVariables();

		String cwd = getWorkingDirectory();
        // Create process file
		File file = createFlattenedPropsFile(_descriptor, cwd);
		System.out.println("Temp file created " + file.getAbsolutePath());

		env.put(JOB_PROP_ENV, file.getAbsolutePath());
	    env.put(JOB_NAME_ENV, _name);
	      
		// For each of the jobs, set up a process and run them.
		for (String command : commands) {
			info("Executing command: " + command);
			String[] cmdPieces = command.split("\\s+");
			ProcessBuilder builder = new ProcessBuilder(cmdPieces);

			builder.directory(new File(cwd));
			builder.environment().putAll(env);

			try {
				_process = builder.start();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			Thread outputGobbler = new LoggingGobbler(new InputStreamReader(
					_process.getInputStream()), Level.INFO);
			Thread errorGobbler = new LoggingGobbler(new InputStreamReader(
					_process.getErrorStream()), Level.ERROR);
			outputGobbler.start();
			errorGobbler.start();
			int exitCode = 0;
			try {
				exitCode = _process.waitFor();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}

			_isComplete = true;
			if (exitCode != 0)
				throw new RuntimeException("Processes ended with exit code "
						+ exitCode + ".");

			// try to wait for everything to get logged out before exiting
			try {
				outputGobbler.join(1000);
				errorGobbler.join(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		
		file.delete();
	}

	private File createFlattenedPropsFile(JobDescriptor desc, String workingDir)  {
	    File directory = new File(workingDir);
	    File tempFile = null;
	    try {
	        tempFile = File.createTempFile(desc.getId() + "_", "_tmp", directory);
	        desc.getProps().storeFlattened(tempFile);
	    } catch (IOException e) {
            throw new RuntimeException("Failed to create temp property file ", e);
	    }
	    
	    return tempFile;
	}
	
	protected List<String> getCommandList() {
		List<String> commands = new ArrayList<String>();
		commands.add(_props.getString(COMMAND));
		for (int i = 1; _props.containsKey(COMMAND + "." + i); i++)
			commands.add(_props.getString(COMMAND + "." + i));

		return commands;
	}

	protected Map<String, String> getEnvironmentVariables() {
		return getMapFromPrefixProperties(ENV_PREFIX);
	}

	protected String getWorkingDirectory() {
		return _props.containsKey(WORKING_DIR) ? _props.getString(WORKING_DIR)
				: new File(_jobPath).getParent();
	}

	protected final Map<String, String> getMapFromPrefixProperties(String prefix) {
		Map<String, String> prefixProperties = new HashMap<String, String>();
		Set<String> keys = _props.keySet();
		for (String key : keys) {
			if (key.toLowerCase().startsWith(prefix)) {
				String value = _props.getString(key);
				String strippedKey = key.substring(prefix.length());
				prefixProperties.put(strippedKey, value);
			}
		}

		return prefixProperties;
	}

	@Override
	public void cancel() throws Exception {
		if (_process != null)
			_process.destroy();
	}

	@Override
	public double getProgress() {
		return _isComplete ? 1.0 : 0.0;
	}

	private class LoggingGobbler extends Thread {

		private final BufferedReader _inputReader;
		private final Level _loggingLevel;

		public LoggingGobbler(InputStreamReader inputReader, Level level) {
			_inputReader = new BufferedReader(inputReader);
			_loggingLevel = level;
		}

		@Override
		public void run() {
			try {
				while (!Thread.currentThread().isInterrupted()) {
					String line = _inputReader.readLine();
					if (line == null)
						return;
					
					logMessage(line);
				}
			} catch (IOException e) {
				error("Error reading from logging stream:", e);
			}
		}
		
		private void logMessage(String message) {
		    if (message.startsWith(Level.DEBUG.toString())) {
		        String newMsg = message.substring(Level.DEBUG.toString().length());
		        getLog().debug(newMsg);
		    }
		    else if (message.startsWith(Level.ERROR.toString())) {
		        String newMsg = message.substring(Level.ERROR.toString().length());
                getLog().error(newMsg);
		    }
	        else if (message.startsWith(Level.INFO.toString())) {
	            String newMsg = message.substring(Level.INFO.toString().length());
                getLog().info(newMsg);   
	        }
            else if (message.startsWith(Level.WARN.toString())) {
                String newMsg = message.substring(Level.WARN.toString().length());
                getLog().warn(newMsg);
            }
            else if (message.startsWith(Level.FATAL.toString())) {
                String newMsg = message.substring(Level.FATAL.toString().length());
                getLog().fatal(newMsg);
            }
            else if (message.startsWith(Level.TRACE.toString())) {
                String newMsg = message.substring(Level.TRACE.toString().length());
                getLog().trace(newMsg);
            }
            else {
                getLog().log(_loggingLevel, message);
            }
		    
		}
	}

	public Props getProps() {
		return _props;
	}

	public String getPath() {
		return _jobPath;
	}
	
	public String getJobName() {
		return _name;
	}
	
	public JobDescriptor getJobDescriptor() {
	    return _descriptor;
	}
}
