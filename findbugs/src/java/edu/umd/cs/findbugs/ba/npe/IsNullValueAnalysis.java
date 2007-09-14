/*
 * Bytecode Analysis Framework
 * Copyright (C) 2003-2005 University of Maryland
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

package edu.umd.cs.findbugs.ba.npe;

import java.util.BitSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.apache.bcel.Constants;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.CodeExceptionGen;
import org.apache.bcel.generic.Instruction;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.MethodGen;
import org.apache.bcel.generic.ObjectType;
import org.apache.bcel.generic.Type;

import edu.umd.cs.findbugs.SystemProperties;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.ba.AnalysisContext;
import edu.umd.cs.findbugs.ba.AnalysisFeatures;
import edu.umd.cs.findbugs.ba.AssertionMethods;
import edu.umd.cs.findbugs.ba.BasicBlock;
import edu.umd.cs.findbugs.ba.CFG;
import edu.umd.cs.findbugs.ba.CFGBuilderException;
import edu.umd.cs.findbugs.ba.ClassContext;
import edu.umd.cs.findbugs.ba.Dataflow;
import edu.umd.cs.findbugs.ba.DataflowAnalysisException;
import edu.umd.cs.findbugs.ba.DataflowTestDriver;
import edu.umd.cs.findbugs.ba.DepthFirstSearch;
import edu.umd.cs.findbugs.ba.Edge;
import edu.umd.cs.findbugs.ba.EdgeTypes;
import edu.umd.cs.findbugs.ba.FrameDataflowAnalysis;
import edu.umd.cs.findbugs.ba.INullnessAnnotationDatabase;
import edu.umd.cs.findbugs.ba.JavaClassAndMethod;
import edu.umd.cs.findbugs.ba.Location;
import edu.umd.cs.findbugs.ba.NullnessAnnotation;
import edu.umd.cs.findbugs.ba.XFactory;
import edu.umd.cs.findbugs.ba.XMethod;
import edu.umd.cs.findbugs.ba.XMethodParameter;
import edu.umd.cs.findbugs.ba.vna.AvailableLoad;
import edu.umd.cs.findbugs.ba.vna.ValueNumber;
import edu.umd.cs.findbugs.ba.vna.ValueNumberDataflow;
import edu.umd.cs.findbugs.ba.vna.ValueNumberFrame;

/**
 * A dataflow analysis to detect potential null pointer dereferences.
 *
 * @author David Hovemeyer
 * @see IsNullValue
 * @see IsNullValueFrame
 * @see IsNullValueFrameModelingVisitor
 */
public class IsNullValueAnalysis
		extends FrameDataflowAnalysis<IsNullValue, IsNullValueFrame>
		implements EdgeTypes, IsNullValueAnalysisFeatures {
	static final boolean DEBUG = SystemProperties.getBoolean("inva.debug");

	static {
		if (DEBUG) System.out.println("inva.debug enabled");
	}

	private MethodGen methodGen;
	private IsNullValueFrameModelingVisitor visitor;
	private ValueNumberDataflow vnaDataflow;
	private CFG cfg;
	private Set<LocationWhereValueBecomesNull> locationWhereValueBecomesNullSet;
	private final boolean trackValueNumbers;

	private IsNullValueFrame lastFrame;
	private IsNullValueFrame instanceOfFrame;
	private IsNullValueFrame cachedEntryFact;

	private JavaClassAndMethod classAndMethod;

	public IsNullValueAnalysis(MethodGen methodGen, CFG cfg, ValueNumberDataflow vnaDataflow, DepthFirstSearch dfs,
							   AssertionMethods assertionMethods) {
		super(dfs);

		this.trackValueNumbers = AnalysisContext.currentAnalysisContext().getBoolProperty(
				AnalysisFeatures.TRACK_VALUE_NUMBERS_IN_NULL_POINTER_ANALYSIS);

		this.methodGen = methodGen;
		this.visitor = new IsNullValueFrameModelingVisitor(
				methodGen.getConstantPool(),
				assertionMethods,
				vnaDataflow,
				trackValueNumbers);
		this.vnaDataflow = vnaDataflow;
		this.cfg = cfg;
		this.locationWhereValueBecomesNullSet = new HashSet<LocationWhereValueBecomesNull>();

		if (DEBUG) {
			System.out.println("IsNullValueAnalysis for " + methodGen.getClassName() + "." + methodGen.getName() + " : " + methodGen.getSignature());
		}
	}

	public void setClassAndMethod(JavaClassAndMethod classAndMethod) {
		this.classAndMethod = classAndMethod;
	}
	public JavaClassAndMethod getClassAndMethod( ) {
		return  classAndMethod;
	}
	public IsNullValueFrame createFact() {
		return new IsNullValueFrame(methodGen.getMaxLocals(), trackValueNumbers);
	}



	public void initEntryFact(IsNullValueFrame result) {
		if (cachedEntryFact ==  null) {

			cachedEntryFact = createFact();
			cachedEntryFact.setValid();

			int numLocals = methodGen.getMaxLocals();
			boolean instanceMethod = !methodGen.isStatic();
			XMethod xm = XFactory.createXMethod(methodGen.getClassName(), 
					methodGen.getName(), methodGen.getSignature(), methodGen.isStatic());
			INullnessAnnotationDatabase db = AnalysisContext.currentAnalysisContext().getNullnessAnnotationDatabase();
			int paramShift = instanceMethod ? 1 : 0;
			 Type[] argumentTypes = methodGen.getArgumentTypes();
            for (int i = 0; i < numLocals; ++i) {
				cachedEntryFact.setValue(i, IsNullValue.nonReportingNotNullValue());
			}
			if (paramShift == 1)
				cachedEntryFact.setValue(0,IsNullValue.nonNullValue());
			
			int slot = paramShift;
			for (int paramIndex = 0; paramIndex < argumentTypes.length ; paramIndex++) {
				IsNullValue value;

				XMethodParameter methodParameter = new XMethodParameter(xm, paramIndex);
				NullnessAnnotation n = db.getResolvedAnnotation(methodParameter, false);
				if (n == NullnessAnnotation.CHECK_FOR_NULL)
					// Parameter declared @CheckForNull
					value = IsNullValue.parameterMarkedAsMightBeNull(methodParameter);
				else if (n == NullnessAnnotation.NONNULL) 
					// Parameter declared @NonNull
					// TODO: label this so we don't report defensive programming
					value = IsNullValue.nonNullValue();
				else 
					// Don't know; use default value, normally non-reporting nonnull
					value = IsNullValue.nonReportingNotNullValue();

				cachedEntryFact.setValue(slot, value);

				slot += argumentTypes[paramIndex].getSize();
				}
		}
		copy(cachedEntryFact, result);
	}


	@Override
	public void transfer(BasicBlock basicBlock, @CheckForNull InstructionHandle end, IsNullValueFrame start, IsNullValueFrame result)
	throws DataflowAnalysisException {
		startTransfer();
		super.transfer(basicBlock, end, start, result);
		endTransfer(basicBlock, end, result);
		ValueNumberFrame vnaFrameAfter = vnaDataflow.getFactAfterLocation(Location.getLastLocation(basicBlock));
		// purge stale information
		if (!vnaFrameAfter.isTop())
			result.cleanStaleKnowledge(vnaFrameAfter);
	}

	public void startTransfer() throws DataflowAnalysisException {
		lastFrame = null;
		instanceOfFrame = null;
	}

	public void endTransfer(BasicBlock basicBlock, @CheckForNull InstructionHandle end, IsNullValueFrame result)
			throws DataflowAnalysisException {
		// Determine if this basic block ends in a redundant branch.
		if (end == null) {
			if (lastFrame == null)
				result.setDecision(null);
			else {
				IsNullConditionDecision decision = getDecision(basicBlock, lastFrame);
				//if (DEBUG) System.out.println("Decision=" + decision);
				result.setDecision(decision);
			}
		}
		lastFrame = null;
		instanceOfFrame = null;
	}

	@Override
	public void transferInstruction(InstructionHandle handle, BasicBlock basicBlock, IsNullValueFrame fact)
			throws DataflowAnalysisException {

		// If this is the last instruction in the block,
		// save the result immediately before the instruction.
		if (handle == basicBlock.getLastInstruction()) {
			lastFrame = createFact();
			lastFrame.copyFrom(fact);
		}

		if (handle.getInstruction().getOpcode() == Constants.INSTANCEOF) {
			instanceOfFrame = createFact();
			instanceOfFrame.copyFrom(fact);
		}

		// Model the instruction
		visitor.setFrameAndLocation(fact, new Location(handle, basicBlock));
		Instruction ins = handle.getInstruction();
		visitor.analyzeInstruction(ins);

		// Special case:
		// The instruction may have produced previously seen values
		// about which new is-null information is known.
		// If any other instances of the produced values exist,
		// update their is-null information.
		// Also, make a note of any newly-produced null values.

		int numProduced = ins.produceStack(methodGen.getConstantPool());
		if (numProduced == Constants.UNPREDICTABLE)
			throw new DataflowAnalysisException("Unpredictable stack production", methodGen, handle);

		int start = fact.getNumSlots() - numProduced;
		Location location = new Location(handle, basicBlock);
		ValueNumberFrame vnaFrameAfter = vnaDataflow.getFactAfterLocation(location);
		if (!vnaFrameAfter.isValid()) {
			assert false:  "Invalid VNA after location " + location;
			// vnaDataflow.dumpDataflow();
			return;
		}
		for (int i = start; i < fact.getNumSlots(); ++i) {
			ValueNumber value = vnaFrameAfter.getValue(i);
			IsNullValue isNullValue = fact.getValue(i);

			for (int j = 0; j < start; ++j) {
				ValueNumber otherValue = vnaFrameAfter.getValue(j);
				if (value.equals(otherValue)) {
					// Same value is in both slots.
					// Update the is-null information to match
					// the new information.
					fact.setValue(j, isNullValue);
				}
			}
		}

		if (visitor.getSlotContainingNewNullValue() >= 0) {
			ValueNumber newNullValue = vnaFrameAfter.getValue(visitor.getSlotContainingNewNullValue());
			addLocationWhereValueBecomesNull(new LocationWhereValueBecomesNull(
					location,
					newNullValue//,
					//handle
			));
		}

	}

	private static final BitSet nullComparisonInstructionSet = new BitSet();

	static {
		nullComparisonInstructionSet.set(Constants.IFNULL);

		nullComparisonInstructionSet.set(Constants.IFNONNULL);
		nullComparisonInstructionSet.set(Constants.IF_ACMPEQ);
		nullComparisonInstructionSet.set(Constants.IF_ACMPNE);
	}

	public void meetInto(IsNullValueFrame fact, Edge edge, IsNullValueFrame result)
	throws DataflowAnalysisException {
		meetInto(fact,edge,result,true);
	}
	public void meetInto(IsNullValueFrame fact, Edge edge, IsNullValueFrame result, boolean propagatePhiNodeInformation)
			throws DataflowAnalysisException {

		if (fact.isValid()) {
			IsNullValueFrame tmpFact = null;

			if (!NO_SPLIT_DOWNGRADE_NSP) {
				// Downgrade NSP to DNR on non-exception control splits
				if (!edge.isExceptionEdge()
						&& cfg.getNumNonExceptionSucessors(edge.getSource()) > 1) {
					tmpFact = modifyFrame(fact, tmpFact);
					tmpFact.downgradeOnControlSplit();
				}
			}

			if (!NO_SWITCH_DEFAULT_AS_EXCEPTION) {
				if (edge.getType() == SWITCH_DEFAULT_EDGE) {
					tmpFact = modifyFrame(fact, tmpFact);
					tmpFact.toExceptionValues();
				}
			}

			final BasicBlock destBlock = edge.getTarget();

			if (destBlock.isExceptionHandler()) {
				// Exception handler - clear stack and push a non-null value
				// to represent the exception.
				tmpFact = modifyFrame(fact, tmpFact);
				tmpFact.clearStack();

				// Downgrade NULL and NSP to DNR if the handler is for
				// CloneNotSupportedException or InterruptedException
				if (true) {
				CodeExceptionGen handler = destBlock.getExceptionGen();
				ObjectType catchType = handler.getCatchType();
				if (catchType != null) {
					String catchClass = catchType.getClassName();
					if (catchClass.equals("java.lang.CloneNotSupportedException") ||
							catchClass.equals("java.lang.InterruptedException")) {
						for (int i = 0; i < tmpFact.getNumSlots(); ++i) {
							IsNullValue value = tmpFact.getValue(i);
							if (value.isDefinitelyNull() || value.isNullOnSomePath())
								tmpFact.setValue(i, IsNullValue.nullOnComplexPathValue());
						}
					}
				}
				}

				// Mark all values as having occurred on an exception path
				tmpFact.toExceptionValues();

				// Push the exception value
				tmpFact.pushValue(IsNullValue.nonNullValue());
			} else {
				final int edgeType = edge.getType();
				final BasicBlock sourceBlock = edge.getSource();
				final BasicBlock targetBlock = edge.getTarget();
				final ValueNumberFrame targetVnaFrame = vnaDataflow.getStartFact(destBlock);
				final ValueNumberFrame sourceVnaFrame = vnaDataflow.getResultFact(sourceBlock);


				assert targetVnaFrame != null;

				// Determine if the edge conveys any information about the
				// null/non-null status of operands in the incoming frame.
				if (edgeType == IFCMP_EDGE || edgeType == FALL_THROUGH_EDGE) {
					IsNullConditionDecision decision = getResultFact(edge.getSource()).getDecision();
					if (decision != null) {
						if (!decision.isEdgeFeasible(edgeType)) {
							// The incoming edge is infeasible; just use TOP
							// as the start fact for this block.
							tmpFact = createFact();
							tmpFact.setTop();
						} else if (decision.getValue() != null) {
							// A value has been determined for this edge.
							// Use the value to update the is-null information in
							// the start fact for this block.

							if (DEBUG) {
								System.out.println("Updating edge information for " + decision.getValue());
							}
							final Location atIf = new Location(sourceBlock.getLastInstruction(), sourceBlock);
							// TODO: prevIsNullValueFrame is not used
							final IsNullValueFrame prevIsNullValueFrame = getFactAtLocation(atIf);
							final ValueNumberFrame prevVnaFrame = vnaDataflow.getFactAtLocation(atIf);

							IsNullValue decisionValue = decision.getDecision(edgeType);
							if (decisionValue != null) {
								if (decisionValue.isDefinitelyNull()) {
									// Make a note of the value that has become null
									// due to the if comparison.
									addLocationWhereValueBecomesNull(new LocationWhereValueBecomesNull(
											atIf,
											decision.getValue()
									));
								}
								if (DEBUG) {
									System.out.println("Set decision information");
									System.out.println("  " + decision.getValue() + " becomes " + decisionValue);
									System.out.println("  prev available loads: " + prevVnaFrame.availableLoadMapAsString());
									System.out.println("  target available loads: " + targetVnaFrame.availableLoadMapAsString());
								}
								tmpFact = replaceValues(fact, tmpFact, decision.getValue(), prevVnaFrame,
										targetVnaFrame, decisionValue);
							}

						}
					}
				} // if (edgeType == IFCMP_EDGE || edgeType == FALL_THROUGH_EDGE)

				// If this is a fall-through edge from a null check,
				// then we know the value checked is not null.
				if (sourceBlock.isNullCheck() && edgeType == FALL_THROUGH_EDGE) {
					ValueNumberFrame vnaFrame = vnaDataflow.getStartFact(destBlock);
					if (vnaFrame == null)
						throw new IllegalStateException("no vna frame at block entry?");

					Instruction firstInDest = edge.getTarget().getFirstInstruction().getInstruction();


					IsNullValue instance = fact.getInstance(firstInDest, methodGen.getConstantPool());


					if (instance.isDefinitelyNull()) {
						// If we know the variable is null, this edge is infeasible
						tmpFact = createFact();
						tmpFact.setTop();
					}
					else if (!instance.isDefinitelyNotNull()) {
						// If we're not sure that the instance is definitely non-null,
						// update the is-null information for the dereferenced value.
						InstructionHandle kaBoomLocation = targetBlock.getFirstInstruction();
						ValueNumber replaceMe = vnaFrame.getInstance(firstInDest, methodGen.getConstantPool());
						IsNullValue noKaboomNonNullValue = IsNullValue.noKaboomNonNullValue(
														new Location(kaBoomLocation, targetBlock)
														);
						if (DEBUG) {
							System.out.println("Start vna fact: " + vnaFrame);
							System.out.println("inva fact: " + fact);
							System.out.println("\nGenerated NoKaboom value for location " + kaBoomLocation);
							System.out.println("Dereferenced " + instance);
							System.out.println("On fall through from source block " + sourceBlock);
						}
						tmpFact = replaceValues(fact, tmpFact, replaceMe, vnaFrame, targetVnaFrame, noKaboomNonNullValue);
					}
				} // if (sourceBlock.isNullCheck() && edgeType == FALL_THROUGH_EDGE)

				if (propagatePhiNodeInformation && targetVnaFrame.phiNodeForLoads) {
					if (DEBUG) 
						System.out.println("Is phi node for loads");
					for(ValueNumber v : fact.getKnownValues()) {
						AvailableLoad loadForV = sourceVnaFrame.getLoad(v);
						if (DEBUG) {
							System.out.println("  " + v + " for " + loadForV);
						}
						if (loadForV != null) {
							ValueNumber[] matchingValueNumbers = targetVnaFrame.getAvailableLoad(loadForV);
							if (matchingValueNumbers != null)
								for(ValueNumber v2 : matchingValueNumbers) {
									tmpFact = modifyFrame(fact, tmpFact);
									tmpFact.useNewValueNumberForLoad(v, v2);
									if (DEBUG) System.out.println("For " + loadForV + " switch from " + v + " to " + v2);
								}
						}

					}
				}
			}
			if (tmpFact != null)
				fact = tmpFact;
		} // if (fact.isValid())

		// Normal dataflow merge
		mergeInto(fact, result);
	}

	/* (non-Javadoc)
	 * @see edu.umd.cs.findbugs.ba.FrameDataflowAnalysis#mergeInto(edu.umd.cs.findbugs.ba.Frame, edu.umd.cs.findbugs.ba.Frame)
	 */
	@Override
	protected void mergeInto(IsNullValueFrame other, IsNullValueFrame result) throws DataflowAnalysisException {
		if (other.isTop()) return;
		if (result.isTop()) {
			result.copyFrom(other);
			return;	
		}
		super.mergeInto(other, result);
		//FIXME: update decision?
		if (trackValueNumbers) {
			result.mergeKnownValuesWith(other);
		}

	}

	/* (non-Javadoc)
	 * @see edu.umd.cs.findbugs.ba.AbstractDataflowAnalysis#startIteration()
	 */
	@Override
	public void startIteration() {
		// At the beginning of each iteration, clear the set of locations
		// where values become null.  That way, after the final iteration
		// of dataflow analysis the set should be as accurate as possible.
		locationWhereValueBecomesNullSet.clear();
	}

	public void addLocationWhereValueBecomesNull(LocationWhereValueBecomesNull locationWhereValueBecomesNull) {
		// System.out.println("Location becomes null: " + locationWhereValueBecomesNull );
		locationWhereValueBecomesNullSet.add(locationWhereValueBecomesNull);
	}

	public Set<LocationWhereValueBecomesNull> getLocationWhereValueBecomesNullSet() {
		return locationWhereValueBecomesNullSet;
	}

	@Override
	protected void mergeValues(IsNullValueFrame otherFrame, IsNullValueFrame resultFrame, int slot)
			throws DataflowAnalysisException {
		IsNullValue value = IsNullValue.merge(resultFrame.getValue(slot), otherFrame.getValue(slot));
		resultFrame.setValue(slot, value);
	}

	/**
	 * Determine if the given basic block ends in a redundant
	 * null comparison.
	 *
	 * @param basicBlock the basic block
	 * @param lastFrame  the IsNullValueFrame representing values at the final instruction
	 *                   of the block
	 * @return an IsNullConditionDecision object representing the
	 *         is-null information gained about the compared value,
	 *         or null if no information is gained
	 */
	private IsNullConditionDecision getDecision(BasicBlock basicBlock,  IsNullValueFrame lastFrame)
			throws DataflowAnalysisException {

		assert lastFrame != null;

		final InstructionHandle lastInSourceHandle = basicBlock.getLastInstruction();
		if (lastInSourceHandle == null)
			return null; // doesn't end in null comparison

		final short lastInSourceOpcode = lastInSourceHandle.getInstruction().getOpcode();
		// System.out.println("last opcode: " + Constants.OPCODE_NAMES[lastInSourceOpcode]);
		if (lastInSourceOpcode == Constants.IFEQ || lastInSourceOpcode == Constants.IFNE ) {
			InstructionHandle prev = lastInSourceHandle.getPrev();
			if (prev == null) return null;
			short secondToLastOpcode = prev.getInstruction().getOpcode();
			// System.out.println("Second last opcode: " +  Constants.OPCODE_NAMES[secondToLastOpcode]);
			if (secondToLastOpcode != Constants.INSTANCEOF) return null;
			if (instanceOfFrame == null) return null;
			IsNullValue tos = instanceOfFrame.getTopValue();
			boolean isNotInstanceOf = (lastInSourceOpcode != Constants.IFNE);
			Location atInstanceOf = new Location(prev, basicBlock);
			ValueNumberFrame instanceOfVnaFrame = vnaDataflow.getFactAtLocation(atInstanceOf);

			// Initially, assume neither branch is feasible.
			IsNullValue ifcmpDecision = null;
			IsNullValue fallThroughDecision = null;

			if (tos.isDefinitelyNull()) {
				// Predetermined comparison - one branch is infeasible
				if (isNotInstanceOf)
					ifcmpDecision =tos;
				else // ifnonnull
					fallThroughDecision = tos;
			} else if (tos.isDefinitelyNotNull()) {
				return null;
			} else {
				// As far as we know, both branches feasible
				ifcmpDecision = isNotInstanceOf ? tos : IsNullValue.pathSensitiveNonNullValue();
				fallThroughDecision = isNotInstanceOf ? IsNullValue.pathSensitiveNonNullValue() : tos;
			}
			 if (DEBUG) System.out.println("Checking..." + tos + " -> " + ifcmpDecision + " or " + fallThroughDecision);

			return new IsNullConditionDecision(instanceOfVnaFrame.getTopValue(), ifcmpDecision, fallThroughDecision);

		}
		if (!nullComparisonInstructionSet.get(lastInSourceOpcode))
			return null; // doesn't end in null comparison

		Location atIf = new Location(lastInSourceHandle, basicBlock);
		ValueNumberFrame prevVnaFrame = vnaDataflow.getFactAtLocation(atIf);

		switch (lastInSourceOpcode) {

		case Constants.IFNULL:
		case Constants.IFNONNULL:
			{
				IsNullValue tos = lastFrame.getTopValue();
				boolean ifnull = (lastInSourceOpcode == Constants.IFNULL);

				// Initially, assume neither branch is feasible.
				IsNullValue ifcmpDecision = null;
				IsNullValue fallThroughDecision = null;

				if (tos.isDefinitelyNull()) {
					// Predetermined comparison - one branch is infeasible
					if (ifnull)
						ifcmpDecision = IsNullValue.pathSensitiveNullValue();
					else // ifnonnull
						fallThroughDecision = IsNullValue.pathSensitiveNullValue();
				} else if (tos.isDefinitelyNotNull()) {
					// Predetermined comparison - one branch is infeasible
					if (ifnull)
						fallThroughDecision = IsNullValue.pathSensitiveNonNullValue();
					else // ifnonnull
						ifcmpDecision = IsNullValue.pathSensitiveNonNullValue();
				} else {
					// As far as we know, both branches feasible
					ifcmpDecision = ifnull ? IsNullValue.pathSensitiveNullValue() : IsNullValue.pathSensitiveNonNullValue();
					fallThroughDecision = ifnull ? IsNullValue.pathSensitiveNonNullValue() : IsNullValue.pathSensitiveNullValue();
				}
				return new IsNullConditionDecision(prevVnaFrame.getTopValue(), ifcmpDecision, fallThroughDecision);
			}
		case Constants.IF_ACMPEQ:
		case Constants.IF_ACMPNE:
			{
				IsNullValue tos = lastFrame.getStackValue(0);
				IsNullValue nextToTos = lastFrame.getStackValue(1);

				boolean tosNull = tos.isDefinitelyNull();
				boolean nextToTosNull = nextToTos.isDefinitelyNull();

				boolean cmpeq = (lastInSourceOpcode == Constants.IF_ACMPEQ);

				// Initially, assume neither branch is feasible.
				IsNullValue ifcmpDecision = null;
				IsNullValue fallThroughDecision = null;
				ValueNumber value;

				if (tosNull && nextToTosNull) {
					// Redundant comparision: both values are null, only one branch is feasible
					value = null; // no value will be replaced - just want to indicate that one of the branches is infeasible
					if (cmpeq)
						ifcmpDecision = IsNullValue.pathSensitiveNullValue();
					else // cmpne
						fallThroughDecision = IsNullValue.pathSensitiveNullValue();
				} else if (tosNull || nextToTosNull) {
					// We have updated information about whichever value is not null;
					// both branches are feasible
					value = prevVnaFrame.getStackValue(tosNull ? 1 : 0);
					ifcmpDecision = cmpeq ? IsNullValue.pathSensitiveNullValue() : IsNullValue.pathSensitiveNonNullValue();
					fallThroughDecision = cmpeq ? IsNullValue.pathSensitiveNonNullValue() : IsNullValue.pathSensitiveNullValue();
				} else if (tos.isDefinitelyNotNull() && !nextToTos.isDefinitelyNotNull()) {
					// learn that nextToTos is definitely non null on one branch
					value = prevVnaFrame.getStackValue(1);
					if (cmpeq) {
						ifcmpDecision = tos;
						fallThroughDecision = nextToTos;
					} else {
						fallThroughDecision = tos;
						ifcmpDecision = nextToTos;
					}
				} else if (!tos.isDefinitelyNotNull() && nextToTos.isDefinitelyNotNull()) {
					// learn that tos is definitely non null on one branch
					value = prevVnaFrame.getStackValue(0);
					if (cmpeq) {
						ifcmpDecision = nextToTos;
						fallThroughDecision = tos;
					} else {
						fallThroughDecision = nextToTos;
						ifcmpDecision = tos;
					}
				} else {
					// No information gained
					break;
				}

				return new IsNullConditionDecision(value, ifcmpDecision, fallThroughDecision);
			}
		default:
			throw new IllegalStateException();
		}

		return null; // no information gained
	}

	/**
	 * Update is-null information at a branch target based on information gained at a
	 * null comparison branch.
	 *
	 * @param origFrame        the original is-null frame at entry to basic block
	 * @param frame            the modified version of the is-null entry frame;
	 *                         null if the entry frame has not been modified yet
	 * @param replaceMe        the ValueNumber in the value number frame at the if comparison
	 *                         whose is-null information will be updated
	 * @param prevVnaFrame     the ValueNumberFrame at the if comparison
	 * @param targetVnaFrame   the ValueNumberFrame at entry to the basic block
	 * @param replacementValue the IsNullValue representing the updated
	 *                         is-null information
	 * @return a modified IsNullValueFrame with updated is-null information
	 */
	private IsNullValueFrame replaceValues(IsNullValueFrame origFrame, IsNullValueFrame frame,
										   ValueNumber replaceMe, ValueNumberFrame prevVnaFrame, ValueNumberFrame targetVnaFrame, 
										  IsNullValue replacementValue) {

		// If required, make a copy of the frame
		frame = modifyFrame(origFrame, frame);

		assert frame.getNumSlots() == targetVnaFrame.getNumSlots();

		// The VNA frame may have more slots than the IsNullValueFrame
		// if it was produced by an IF comparison (whose operand or operands
		// are subsequently popped off the stack).

		final int targetNumSlots = targetVnaFrame.getNumSlots();
		final int prefixNumSlots = Math.min(frame.getNumSlots(), prevVnaFrame.getNumSlots());

		if (trackValueNumbers) {
			AvailableLoad loadForV = prevVnaFrame.getLoad(replaceMe);
			if (DEBUG && loadForV != null) {
				System.out.println("For " + replaceMe + " availableLoad is " + loadForV);
				ValueNumber[] matchingValueNumbers = targetVnaFrame.getAvailableLoad(loadForV);
				if (matchingValueNumbers != null)
					for(ValueNumber v2 : matchingValueNumbers) System.out.println("  matches " + v2);
			}
			if (loadForV != null) {
				ValueNumber[] matchingValueNumbers = targetVnaFrame.getAvailableLoad(loadForV);
				if (matchingValueNumbers != null)
					for(ValueNumber v2 : matchingValueNumbers) if (!replaceMe.equals(v2)) {
						frame.setKnownValue(v2, replacementValue);
						if (DEBUG) System.out.println("For " + loadForV + " switch from " + replaceMe + " to " + v2);
					}
			}	
			frame.setKnownValue(replaceMe, replacementValue);
		}
		// Here's the deal:
		// - "replaceMe" is the value number from the previous frame (at the if branch)
		//   which indicates a value that we have updated is-null information about
		// - in the target value number frame (at entry to the target block),
		//   we find the value number in the stack slot corresponding to the "replaceMe"
		//   value; this is the "corresponding" value
		// - all instances of the "corresponding" value in the target frame have
		//   their is-null information updated to "replacementValue"
		// This should thoroughly make use of the updated information.

		for (int i = 0; i < prefixNumSlots; ++i) {
			if (prevVnaFrame.getValue(i).equals(replaceMe)) {
				ValueNumber corresponding = targetVnaFrame.getValue(i);
				for (int j = 0; j < targetNumSlots; ++j) {
					if (targetVnaFrame.getValue(j).equals(corresponding))
						frame.setValue(j, replacementValue);
				}
			}
		}

		return frame;

	}

	public IsNullValueFrame getFactAtMidEdge(Edge edge) throws DataflowAnalysisException {
		BasicBlock block = isForwards() ? edge.getSource() : edge.getTarget();

		IsNullValueFrame predFact = createFact();
		copy(getResultFact(block), predFact);

		edgeTransfer(edge, predFact);

		IsNullValueFrame result = createFact();
		makeFactTop(result);
		meetInto(predFact, edge, result, false);

		return result;
	}

	/**
	 * Test driver.
	 */
	public static void main(String[] argv) throws Exception {
		if (argv.length != 1) {
			System.err.println("Usage: " + IsNullValueAnalysis.class.getName() + " <class file>");
			System.exit(1);
		}

		DataflowTestDriver<IsNullValueFrame, IsNullValueAnalysis> driver = new DataflowTestDriver<IsNullValueFrame, IsNullValueAnalysis>() {
			@Override
			public Dataflow<IsNullValueFrame, IsNullValueAnalysis> createDataflow(ClassContext classContext, Method method)
					throws CFGBuilderException, DataflowAnalysisException {

				return classContext.getIsNullValueDataflow(method);
			}
		};

		driver.execute(argv[0]);
	}

}

// vim:ts=4