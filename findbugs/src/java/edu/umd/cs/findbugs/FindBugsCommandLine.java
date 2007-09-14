/*
 * FindBugs - Find bugs in Java programs
 * Copyright (C) 2003-2007 University of Maryland
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package edu.umd.cs.findbugs;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.StringTokenizer;

import org.dom4j.DocumentException;
import org.xml.sax.SAXException;

import edu.umd.cs.findbugs.config.AnalysisFeatureSetting;
import edu.umd.cs.findbugs.config.CommandLine;

/**
 * Base class for FindBugs command line classes.
 * Handles all shared switches/options.
 * 
 * @author David Hovemeyer
 */
public abstract class FindBugsCommandLine extends CommandLine {

	/**
	 * Analysis settings to configure the analysis effort.
	 */
	protected AnalysisFeatureSetting[] settingList = FindBugs.DEFAULT_EFFORT;

	/**
	 * Project to analyze.
	 */
	protected Project project = new Project();

	/**
	 * Constructor.
	 * Adds shared options/switches.
	 */
	public FindBugsCommandLine() {
		addOption("-project", "project", "analyze given project");
		addOption("-home", "home directory", "specify FindBugs home directory");
		addOption("-pluginList", "jar1[" + File.pathSeparator + "jar2...]",
				"specify list of plugin Jar files to load");
		addSwitchWithOptionalExtraPart("-effort", "min|default|max", "set analysis effort level");
		addSwitch("-adjustExperimental", "lower priority of experimental Bug Patterns");
		addSwitch("-workHard", "ensure analysis effort is at least 'default'");
		addSwitch("-conserveSpace", "same as -effort:min (for backward compatibility)");
	}

	public AnalysisFeatureSetting[] getSettingList() {
		return settingList;
	}

	public Project getProject() {
		return project;
	}


	@Override
	protected void handleOption(String option, String optionExtraPart) {
		if (option.equals("-effort")) {
			if (optionExtraPart.equals("min")) {
				settingList = FindBugs.MIN_EFFORT;
			} else if (optionExtraPart.equals("less")) {
				settingList = FindBugs.LESS_EFFORT;
			} else if (optionExtraPart.equals("default")) {
				settingList = FindBugs.DEFAULT_EFFORT;
			} else if (optionExtraPart.equals("more")) {
				settingList = FindBugs.MORE_EFFORT;
			} else if (optionExtraPart.equals("max")) {
				settingList = FindBugs.MAX_EFFORT;
			} else {
				throw new IllegalArgumentException("-effort:<value> must be one of min,default,more,max");
			}
		} else if (option.equals("-workHard")) {
			if (settingList != FindBugs.MAX_EFFORT)
				settingList = FindBugs.MORE_EFFORT;

		} else if (option.equals("-conserveSpace")) {
			settingList = FindBugs.MIN_EFFORT;
		} else if (option.equals("-adjustExperimental")) {
			BugInstance.setAdjustExperimental(true);
		} else {
			throw new IllegalStateException();
		}
	}

	@Override
	protected void handleOptionWithArgument(String option, String argument) throws IOException {
		if (option.equals("-home")) {
			FindBugs.setHome(argument);
		} else if (option.equals("-pluginList")) {
			String pluginListStr = argument;
			ArrayList<URL> pluginList = new ArrayList<URL>();
			StringTokenizer tok = new StringTokenizer(pluginListStr, File.pathSeparator);
			while (tok.hasMoreTokens()) {
				pluginList.add(new File(tok.nextToken()).toURL());
			}

			DetectorFactoryCollection.rawInstance().setPluginList(pluginList.toArray(new URL[pluginList.size()]));
		} else if (option.equals("-project")) {
			project = readProject(argument);
		} else {
			throw new IllegalStateException();
		}
	}

	/**
	 * Read Project from named file.
	 * 
     * @param argument command line argument containing project file name
     * @return the Project
     * @throws IOException
     */
    private Project readProject(String argument) throws IOException {
	    String projectFileName = argument;
	    
	    File projectFile = new File(projectFileName);
	    
	    if (projectFile.isDirectory()) {
	    	// New-style (GUI2) project directory.
	    	// We read in the bug collection in order to read the project
	    	// information as a side effect.
	    	// Inefficient, but effective.
	    	String name = projectFile.getAbsolutePath() + File.separator + projectFile.getName() + ".xml";
	    	File f = new File(name);
	    	SortedBugCollection bugCollection = new SortedBugCollection();

	    	try {
	    		Project project = new Project();
	    		bugCollection.readXML(f.getPath(), project);
	    		return project;
	    	} catch (DocumentException e) {
	    		IOException ioe = new IOException("Couldn't read saved XML in project directory");
	    		ioe.initCause(e);
	    		throw ioe;
	    	}

	    } else if (projectFileName.endsWith(".xml") || projectFileName.endsWith(".fbp")) {
	    	try {
	    	return project = Project.readXML(projectFile);
	    	} catch (DocumentException e) {
	    		IOException ioe = new IOException("Couldn't read saved FindBugs project");
	    		ioe.initCause(e);
	    		throw ioe;
	    	}
	    	catch (SAXException e) {
	    		IOException ioe = new IOException("Couldn't read saved FindBugs project");
	    		ioe.initCause(e);
	    		throw ioe;
	    	}
	    } else {
	    	// Old-style (original GUI) project file

	    	// Convert project file to be an absolute path
	    	projectFileName = new File(projectFileName).getAbsolutePath();

	    	try {
	    		Project project = new Project();
	    		project.read(projectFileName);
	    		return project;
	    	} catch (IOException e) {
	    		System.err.println("Error opening " + projectFileName);
	    		e.printStackTrace(System.err);
	    		throw e;
	    	}
	    }
    }
}