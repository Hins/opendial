// =================================================================                                                                   
// Copyright (C) 2011-2013 Pierre Lison (plison@ifi.uio.no)                                                                            
//                                                                                                                                     
// This library is free software; you can redistribute it and/or                                                                       
// modify it under the terms of the GNU Lesser General Public License                                                                  
// as published by the Free Software Foundation; either version 2.1 of                                                                 
// the License, or (at your option) any later version.                                                                                 
//                                                                                                                                     
// This library is distributed in the hope that it will be useful, but                                                                 
// WITHOUT ANY WARRANTY; without even the implied warranty of                                                                          
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU                                                                    
// Lesser General Public License for more details.                                                                                     
//                                                                                                                                     
// You should have received a copy of the GNU Lesser General Public                                                                    
// License along with this program; if not, write to the Free Software                                                                 
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA                                                                           
// 02111-1307, USA.                                                                                                                    
// =================================================================                                                                   

package opendial.state.rules;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import opendial.arch.DialException;
import opendial.arch.Logger;
import opendial.bn.Assignment;
import opendial.bn.distribs.continuous.FunctionBasedDistribution;
import opendial.bn.nodes.BNode;
import opendial.bn.nodes.ChanceNode;
import opendial.bn.values.Value;
import opendial.domains.datastructs.Output;
import opendial.domains.rules.parameters.Parameter;
import opendial.utils.CombinatoricsUtils;

/**
 * 
 *
 * @author  Pierre Lison (plison@ifi.uio.no)
 * @version $Date::                      $
 *
 */
public class AnchoredRuleCache {

	// logger
	public static Logger log = new Logger("AnchoredValueCache", Logger.Level.DEBUG);
	
	AnchoredRule rule;
	
	Map<Output,Parameter> cachedValues;
	
	public AnchoredRuleCache (AnchoredRule rule) {
		this.rule = rule;
		fillCache();
	}

	

	public Map<Output,Parameter> getOutputs() {
		return cachedValues;
	}
	

	private void fillCache() {
		cachedValues = new HashMap<Output,Parameter>();
		
		Set<Assignment> conditions = getPossibleConditions();
		for (Assignment condition : conditions) {
			Map<Output,Parameter> outputs = rule.getEffectOutputs(condition);
			cachedValues.putAll(outputs);
		}
	}
	
	

	/**
	 * Returns the list of possible assignment of input values for the node.  If the
	 * node has no input, returns a list with a single, empty assignment.
	 * 
	 * <p>NB: this is a combinatorially expensive operation, use with caution.
	 * 
	 * @return the (unordered) list of possible conditions.  
	 */
	protected Set<Assignment> getPossibleConditions() {
		Map<String,Set<Value>> possibleInputValues = new HashMap<String,Set<Value>>();
		for (ChanceNode inputNode : rule.getInputNodes()) {
				possibleInputValues.put(inputNode.getId(), inputNode.getValues());
		}
		
		Set<Assignment> possibleConditions;

		int nbCombinations = 
				CombinatoricsUtils.getEstimatedNbCombinations(possibleInputValues);
		
		if (nbCombinations < 50) {
		possibleConditions = 
				CombinatoricsUtils.getAllCombinations(possibleInputValues);
		}
		else {
			possibleConditions = new HashSet<Assignment>();
			for (int i = 0 ; i < 50 ; i++) {
				Assignment sampledA = new Assignment();
				for (ChanceNode inputNode: rule.getInputNodes()) {
					Value sampledValue = null;
					try { 
						sampledValue = inputNode.sample();
						sampledA.addPair(inputNode.getId(), sampledValue); }
					catch (DialException e) { log.warning("cannot sample " + inputNode.getId()+")"); }
					catch (NullPointerException f) { 
						log.debug("sampledA: " + sampledA);
						log.debug(", inputNodeId: " + inputNode.getId());
						log.debug(", sampled: " + sampledValue); 
					}
				}
				possibleConditions.add(sampledA);
			}
		}
		
		return possibleConditions;
	}

	
}