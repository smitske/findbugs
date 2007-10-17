/*
 * FindBugs - Find bugs in Java programs
 * Copyright (C) 2003-2005 William Pugh
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
package edu.umd.cs.findbugs.workflow;

import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.TreeMap;

import org.dom4j.DocumentException;

import edu.umd.cs.findbugs.AppVersion;
import edu.umd.cs.findbugs.BugCollection;
import edu.umd.cs.findbugs.BugDesignation;
import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.ClassAnnotation;
import edu.umd.cs.findbugs.DetectorFactoryCollection;
import edu.umd.cs.findbugs.PackageStats;
import edu.umd.cs.findbugs.Project;
import edu.umd.cs.findbugs.SortedBugCollection;
import edu.umd.cs.findbugs.TigerSubstitutes;
import edu.umd.cs.findbugs.VersionInsensitiveBugComparator;
import edu.umd.cs.findbugs.PackageStats.ClassStats;
import edu.umd.cs.findbugs.config.CommandLine;
import edu.umd.cs.findbugs.model.MovedClassMap;

/**
 * Java main application to compute update a historical bug collection with
 * results from another build/analysis.
 * 
 * @author William Pugh
 */

public class Update {

	/**
	 * 
	 */
	private static final String USAGE = "Usage: " + Update.class.getName()
			+ " [options]  data1File data2File data3File ... ";

	private HashMap<BugInstance, BugInstance> mapFromNewToOldBug = new HashMap<BugInstance, BugInstance>();

	private HashSet<BugInstance> matchedOldBugs = new HashSet<BugInstance>();

	boolean noPackageMoves = false;

	boolean preciseMatch = false;
	boolean precisePriorityMatch = false;

	class UpdateCommandLine extends CommandLine {
		boolean overrideRevisionNames = false;

		String outputFilename;
		boolean withMessages = false;

		UpdateCommandLine() {
			addSwitch("-overrideRevisionNames",
					"override revision names for each version with names computed filenames");
			addSwitch(
					"-noPackageMoves",
					"if a class seems to have moved from one package to another, treat warnings in that class as two seperate warnings");
			addSwitch("-preciseMatch",
					"require bug patterns to match precisely");
			addSwitch("-precisePriorityMatch",
					"only consider two warnings to be the same if their priorities match exactly");
			addOption("-output", "output file",
					"explicit filename for merged results (standard out used if not specified)");
			addSwitch("-quiet",
					"don't generate any outout to standard out unless there is an error");
			addSwitch("-withMessages",
					"Add bug description");

		}

		@Override
		protected void handleOption(String option, String optionExtraPart)
				throws IOException {
			if (option.equals("-overrideRevisionNames")) {
				if (optionExtraPart.length() == 0)
					overrideRevisionNames = true;
				else
					overrideRevisionNames = TigerSubstitutes
							.parseBoolean(optionExtraPart);
			} else if (option.equals("-noPackageMoves")) {
				if (optionExtraPart.length() == 0)
					noPackageMoves = true;
				else
					noPackageMoves = TigerSubstitutes
							.parseBoolean(optionExtraPart);
			} else if (option.equals("-preciseMatch")) {
				preciseMatch = true;
			} else if (option.equals("-precisePriorityMatch")) {
				versionInsensitiveBugComparator.setComparePriorities(true);
				fuzzyBugPatternMatcher.setComparePriorities(true);
				precisePriorityMatch = true;
			} else if (option.equals("-quiet"))
				verbose = false;
			else if (option.equals("-withMessages"))
				withMessages = true;
			else
				throw new IllegalArgumentException("no option " + option);

		}

		@Override
		protected void handleOptionWithArgument(String option, String argument)
				throws IOException {
			if (option.equals("-output"))
				outputFilename = argument;
			else
				throw new IllegalArgumentException("Can't handle option "
						+ option);

		}

	}

	VersionInsensitiveBugComparator versionInsensitiveBugComparator = new VersionInsensitiveBugComparator();

	VersionInsensitiveBugComparator fuzzyBugPatternMatcher = new VersionInsensitiveBugComparator();
	{
		fuzzyBugPatternMatcher.setExactBugPatternMatch(false);
	}

	HashSet<String> sourceFilesInCollection(BugCollection collection) {
		HashSet<String> result = new HashSet<String>();
		for(PackageStats pStats : collection.getProjectStats().getPackageStats()) {
			for(ClassStats cStats : pStats.getClassStats()) {
				result.add(cStats.getSourceFile());
			}
		}
		return result;
	}
	
	public void removeBaselineBugs(BugCollection baselineCollection,
			BugCollection bugCollection) {

		matchBugs(baselineCollection, bugCollection);
		matchBugs(SortedBugCollection.BugInstanceComparator.instance,
				baselineCollection, bugCollection);
		matchBugs(versionInsensitiveBugComparator, baselineCollection,
				bugCollection);
		for (Iterator<BugInstance> i  = bugCollection.getCollection().iterator(); i.hasNext(); ) {
			BugInstance bug = i.next();
			if (matchedOldBugs.contains(bug)) i.remove();
		}
			
	
	}

	public BugCollection mergeCollections(BugCollection origCollection,
			BugCollection newCollection, boolean copyDeadBugs, boolean incrementalAnalysis) {

		
		mapFromNewToOldBug.clear();

		matchedOldBugs.clear();
		BugCollection resultCollection = newCollection
				.createEmptyCollectionWithMetadata();
		// Previous sequence number
		long lastSequence = origCollection.getSequenceNumber();
		// The AppVersion history is retained from the orig collection,
		// adding an entry for the sequence/timestamp of the current state
		// of the orig collection.
		resultCollection.clearAppVersions();
		for (Iterator<AppVersion> i = origCollection.appVersionIterator(); i
				.hasNext();) {
			AppVersion appVersion = i.next();
			resultCollection.addAppVersion((AppVersion) appVersion.clone());
		}
		AppVersion origCollectionVersion = origCollection.getCurrentAppVersion();
		AppVersion origCollectionVersionClone = new AppVersion(lastSequence);
		origCollectionVersionClone.setTimestamp(origCollectionVersion.getTimestamp());
		origCollectionVersionClone.setReleaseName(origCollectionVersion.getReleaseName());
		origCollectionVersionClone.setNumClasses(origCollection.getProjectStats()
				.getNumClasses());
		origCollectionVersionClone.setCodeSize(origCollection.getProjectStats()
				.getCodeSize());

		resultCollection.addAppVersion(origCollectionVersionClone);

		// We assign a sequence number to the new collection as one greater than
		// the original collection.
		long currentSequence = origCollection.getSequenceNumber() + 1;
		resultCollection.setSequenceNumber(currentSequence);

		int oldBugs = 0;
		// move all inactive bugs
		if (copyDeadBugs)
			for (BugInstance bug : origCollection.getCollection())
				if (bug.getLastVersion() != -1) {
					oldBugs++;
					BugInstance newBug = (BugInstance) bug.clone();
					resultCollection.add(newBug, false);
				}

		matchBugs(origCollection, newCollection);

		// matchBugs(new SloppyBugComparator(), origCollection, newCollection);

		int newlyDeadBugs = 0;
		int persistantBugs = 0;
		int addedBugs = 0;
		int addedInNewCode = 0;
		int deadBugInDeadCode = 0;

		HashSet<String> analyzedSourceFiles = sourceFilesInCollection(newCollection);
		// Copy unmatched bugs
		if (copyDeadBugs || incrementalAnalysis)
			for (BugInstance bug : origCollection.getCollection())
				if (!matchedOldBugs.contains(bug) && bug.getLastVersion() == -1) {
					newlyDeadBugs++;

					BugInstance newBug = (BugInstance) bug.clone();

					ClassAnnotation classBugFoundIn = bug.getPrimaryClass();
					String className = classBugFoundIn.getClassName();
					String sourceFile = classBugFoundIn.getSourceFileName();
					boolean removed = sourceFile != null && analyzedSourceFiles.contains(sourceFile) || newCollection.getProjectStats().getClassStats(className) != null;
					if (removed) {
						if (!copyDeadBugs)
							continue;
						newBug.setRemovedByChangeOfPersistingClass(true);
						newBug.setLastVersion(lastSequence);
					} else {
						deadBugInDeadCode++;
						if (!incrementalAnalysis)
							newBug.setLastVersion(lastSequence);
					}

					if (newBug.getLastVersion() != -1 && newBug.getFirstVersion() > newBug.getLastVersion())
						throw new IllegalStateException("Illegal Version range: " + newBug.getFirstVersion() + ".."
								+ newBug.getLastVersion());
					resultCollection.add(newBug, false);
				}
		// Copy matched bugs
		for (BugInstance bug : newCollection.getCollection()) {
			BugInstance newBug = (BugInstance) bug.clone();
			if (mapFromNewToOldBug.containsKey(bug)) {
				BugInstance origWarning = mapFromNewToOldBug.get(bug);
				assert origWarning.getLastVersion() == -1;

				copyBugHistory(origWarning, newBug);
				// handle getAnnotationText()/setAnnotationText() and
				// designation key
				BugDesignation designation = newBug.getUserDesignation();
				if (designation != null)
					designation.merge(origWarning.getUserDesignation());
				else
					newBug.setUserDesignation(origWarning.getUserDesignation()); // clone??

				persistantBugs++;
			} else {
				newBug.setFirstVersion(lastSequence + 1);
				addedBugs++;

				ClassAnnotation classBugFoundIn = bug.getPrimaryClass();

				String className = classBugFoundIn.getClassName();
				if (origCollection.getProjectStats().getClassStats(className) != null) {
					newBug.setIntroducedByChangeOfExistingClass(true);
					// System.out.println("added bug to existing code " +
					// newBug.getUniqueId() + " : " + newBug.getAbbrev() + " in
					// " + classBugFoundIn);
				} else
					addedInNewCode++;
			}
			assert newBug.getLastVersion() == -1;
			if (newBug.getLastVersion() != -1)
				throw new IllegalStateException("Illegal Version range: "
						+ newBug.getFirstVersion() + ".."
						+ newBug.getLastVersion());
			int oldSize = resultCollection.getCollection().size();
			resultCollection.add(newBug, false);
			int newSize = resultCollection.getCollection().size();
			if (newSize != oldSize + 1) {
				System.out.println("Failed to add bug #" + newBug.getUniqueId()
						+ " : " + newBug.getMessage());
			}
		}
		if (false && verbose) {
			System.out.println(origCollection.getCollection().size()
					+ " orig bugs, " + newCollection.getCollection().size()
					+ " new bugs");
			System.out.println("Bugs: " + oldBugs + " old, "
					+ deadBugInDeadCode + " in removed code, "
					+ (newlyDeadBugs - deadBugInDeadCode) + " died, "
					+ persistantBugs + " persist, " + addedInNewCode
					+ " in new code, " + (addedBugs - addedInNewCode)
					+ " added");
			System.out.println(resultCollection.getCollection().size()
					+ " resulting bugs");
		}
		return resultCollection;

	}

	/**
     * @param origCollection
     * @param newCollection
     */
    private void matchBugs(BugCollection origCollection, BugCollection newCollection) {
	    matchBugs(SortedBugCollection.BugInstanceComparator.instance,
				origCollection, newCollection);
	    
		mapFromNewToOldBug.clear();
		matchedOldBugs.clear();
		
		matchBugs(versionInsensitiveBugComparator, origCollection,
				newCollection);
		if (!preciseMatch) {
			matchBugs(fuzzyBugPatternMatcher, origCollection, newCollection);
		}
		if (!noPackageMoves) {
			VersionInsensitiveBugComparator movedBugComparator = new VersionInsensitiveBugComparator();
			MovedClassMap movedClassMap = new MovedClassMap(
								origCollection, newCollection).execute();
			if (!movedClassMap.isEmpty()) {
				movedBugComparator.setClassNameRewriter(movedClassMap);
				movedBugComparator.setComparePriorities(precisePriorityMatch);
				matchBugs(movedBugComparator, origCollection, newCollection);
				if (!preciseMatch) {
					movedBugComparator.setExactBugPatternMatch(false);
					matchBugs(movedBugComparator, origCollection, newCollection);
				}
			}
		}
    }

	boolean verbose = true;

	public static String[] getFilePathParts(String filePath) {
		String regex = (File.separatorChar=='\\' ? "\\\\" : File.separator);
		return filePath.split(regex);
	}

	public static void main(String[] args) throws IOException,
			DocumentException {
		new Update().doit(args);
	}

	public void doit(String[] args) throws IOException, DocumentException {

		DetectorFactoryCollection.instance();
		UpdateCommandLine commandLine = new UpdateCommandLine();
		int argCount = commandLine.parse(args, 1, Integer.MAX_VALUE, USAGE);

		if (commandLine.outputFilename == null)
			verbose = false;
		String[] firstPathParts = getFilePathParts(args[argCount]);
		int commonPrefix = firstPathParts.length;
		for (int i = argCount + 1; i <= (args.length - 1); i++) {

			commonPrefix = Math.min(commonPrefix, lengthCommonPrefix(
					firstPathParts, getFilePathParts(args[i])));
		}

		String origFilename = args[argCount++];
		Project project = new Project();
		BugCollection origCollection;
		origCollection = new SortedBugCollection();
		if (verbose)
			System.out.println("Starting with " + origFilename);

		origCollection.readXML(origFilename, project);

		if (commandLine.overrideRevisionNames
				|| origCollection.getReleaseName() == null
				|| origCollection.getReleaseName().length() == 0) {
			
			if (commonPrefix >= firstPathParts.length) {
				// This should only happen if either
				//
				//   (1) there is only one input file, or
				//   (2) all of the input files have the same name
				//
				// In either case, make the release name the same
				// as the file part of the input file(s).
				commonPrefix = firstPathParts.length - 1;
			}
			
			origCollection.setReleaseName(firstPathParts[commonPrefix]);
		}
		
		for (BugInstance bug : origCollection.getCollection())
			if (bug.getLastVersion() >= 0
					&& bug.getFirstVersion() > bug.getLastVersion())
				throw new IllegalStateException("Illegal Version range: "
						+ bug.getFirstVersion() + ".." + bug.getLastVersion());

		while (argCount <= (args.length - 1)) {

			BugCollection newCollection = new SortedBugCollection();

			String newFilename = args[argCount++];
			if (verbose)
				System.out.println("Merging " + newFilename);
			project = new Project();
			try {
				File f = new File(newFilename);
				if (f.length() == 0) {
					if (verbose)
						System.out.println("Empty input file: " + f);
					continue;
				}
				newCollection.readXML(newFilename, project);

				if (commandLine.overrideRevisionNames
						|| newCollection.getReleaseName() == null
						|| newCollection.getReleaseName().length() == 0)
					newCollection
							.setReleaseName(getFilePathParts(newFilename)[commonPrefix]);

				origCollection = mergeCollections(origCollection,
						newCollection, true, false);
			} catch (IOException e) {
				if (verbose)
					System.out.println(e);
				else
					throw e;
			}
		}

		origCollection.setWithMessages(commandLine.withMessages);
		if (commandLine.outputFilename != null)
			origCollection.writeXML(commandLine.outputFilename, project);
		else
			origCollection.writeXML(System.out, project);

	}

	private static int lengthCommonPrefix(String[] string, String[] string2) {
		int maxLength = Math.min(string.length, string2.length);
		for (int result = 0; result < maxLength; result++)
			if (!string[result].equals(string2[result]))
				return result;
		return maxLength;
	}

	private static void copyBugHistory(BugInstance src, BugInstance dest) {

		dest.setFirstVersion(src.getFirstVersion());
		dest.setLastVersion(src.getLastVersion());
		dest.setIntroducedByChangeOfExistingClass(src
				.isIntroducedByChangeOfExistingClass());
		dest.setRemovedByChangeOfPersistingClass(src
				.isRemovedByChangeOfPersistingClass());
	}

	private void matchBugs(Comparator<BugInstance> bugInstanceComparator,
			BugCollection origCollection, BugCollection newCollection) {

		TreeMap<BugInstance, LinkedList<BugInstance>> set = new TreeMap<BugInstance, LinkedList<BugInstance>>(
				bugInstanceComparator);
		int oldBugs = 0;
		int newBugs = 0;
		int matchedBugs = 0;
		for (BugInstance bug : origCollection.getCollection())
			if (bug.getLastVersion() == -1 && !matchedOldBugs.contains(bug)) {
				oldBugs++;
				LinkedList<BugInstance> q = set.get(bug);
				if (q == null) {
					q = new LinkedList<BugInstance>();
					set.put(bug, q);
				}
				q.add(bug);
			}
		for (BugInstance bug : newCollection.getCollection())
			if (!mapFromNewToOldBug.containsKey(bug)) {
				newBugs++;
				LinkedList<BugInstance> q = set.get(bug);
				if (q != null && !q.isEmpty()) {
					matchedBugs++;
					BugInstance matchedBug = q.removeFirst();
					mapFromNewToOldBug.put(bug, matchedBug);
					matchedOldBugs.add(matchedBug);
				}
			}
		if (false && verbose)
			System.out.println("matched " + matchedBugs + " of " + oldBugs
					+ "o/" + newBugs + "n bugs using "
					+ bugInstanceComparator.getClass().getName());
	}

}