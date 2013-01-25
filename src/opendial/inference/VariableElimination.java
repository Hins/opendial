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

package opendial.inference;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import opendial.arch.DialException;
import opendial.arch.Logger;
import opendial.arch.Logger.Level;
import opendial.bn.Assignment;
import opendial.bn.BNetwork;
import opendial.bn.distribs.ProbDistribution;
import opendial.bn.distribs.discrete.DiscreteProbDistribution;
import opendial.bn.distribs.discrete.DiscreteProbabilityTable;
import opendial.bn.distribs.discrete.SimpleTable;
import opendial.bn.distribs.utility.UtilityDistribution;
import opendial.bn.distribs.utility.UtilityTable;
import opendial.bn.nodes.ActionNode;
import opendial.bn.nodes.BNode;
import opendial.bn.nodes.ChanceNode;
import opendial.bn.nodes.DerivedActionNode;
import opendial.bn.nodes.UtilityNode;
import opendial.bn.values.Value;
import opendial.bn.values.ValueFactory;
import opendial.inference.datastructs.DistributionCouple;
import opendial.inference.datastructs.DoubleFactor;
import opendial.inference.queries.Query;
import opendial.inference.queries.ReductionQuery;
import opendial.inference.queries.UtilQuery;
import opendial.state.RuleInstantiator;
import opendial.utils.CombinatoricsUtils;
import opendial.utils.InferenceUtils;

/**
 * Implementation of the Variable Elimination algorithm
 *
 * NB: make this more efficient by discarding irrelevant variables!
 * also see Koller's book to compare the algorithm
 * 
 * @author  Pierre Lison (plison@ifi.uio.no)
 * @version $Date:: 2012-01-03 16:02:01 #$
 *
 */
public class VariableElimination extends AbstractInference implements InferenceAlgorithm {

	static Logger log = new Logger("VariableElimination", Logger.Level.DEBUG);


	// ===================================
	//  MAIN QUERY METHOD
	// ===================================


	/**
	 * Computes the probability and utility distributions for the query variables 
	 * in the Bayesian Network, given the evidence
	 * 
	 * @param query the full query
	 * @return the distribution couple (probability/utility)
	 * @throws DialException if the Bayesian network is ill-formed
	 */
	@Override
	protected DistributionCouple queryJoint(Query query) throws DialException {

		// special case if the query is a utility query without any query variables
		if (query instanceof UtilQuery && query.getQueryVars().isEmpty()) {
			return queryWithoutVars((UtilQuery)query);
		}

		// normal case
		DoubleFactor queryFactor = createQueryFactor(query);

		queryFactor.normalise();

		SimpleTable expandedTable = addEvidencePairs (query, queryFactor.getProbMatrix());
	
		return new DistributionCouple(expandedTable,
				new UtilityTable(queryFactor.getUtilityMatrix()));

	}


	// ===================================
	//  INFERENCE OPERATION METHODS 
	// ===================================


	/**
	 * Generates the full double factor associated with the query variables,
	 * using the variable-elimination algorithm.
	 * 
	 * @param query the query
	 * @return the full double factor containing all query variables
	 * @throws DialException if an error occurred during the inference
	 */
	private DoubleFactor createQueryFactor(Query query) throws DialException {

		Collection<String> queryVars = query.getQueryVars();
		Assignment evidence = query.getEvidence();

		List<DoubleFactor> factors = new LinkedList<DoubleFactor>();

		//	log.debug("query P("+queryVars + "|" + evidence+"), ignoring " + nodesToIgnore);

		for (BNode n: query.getFilteredSortedNodes()) {

			// create the basic factor for every variable
			DoubleFactor basicFactor = makeFactor(n, evidence);
			if (!basicFactor.isEmpty()) {
				factors.add(basicFactor);

				// if the variable is hidden, we sum it out
				if (!queryVars.contains(n.getId())) {
					// && !evidence.containsVar(n.getId() ??
					factors = sumOut(n.getId(), factors);
				}
			}
		}
		// compute the final product, and normalise
		DoubleFactor finalProduct = pointwiseProduct(factors);

		return finalProduct;
	}



	/**
	 * Sums out the variable from the pointwise product of the factors, 
	 * and returns the result
	 * 
	 * @param the Bayesian node corresponding to the variable
	 * @param factors the factors to sum out
	 * @return the summed out factor
	 */
	private List<DoubleFactor> sumOut(String nodeId, List<DoubleFactor> factors) {	

		// we divide the factors into two lists: the factors which are
		// independent of the variable, and those who aren't
		List<DoubleFactor> dependentFactors = new LinkedList<DoubleFactor>();
		List<DoubleFactor> remainingFactors = new LinkedList<DoubleFactor>();

		for (DoubleFactor f: factors) {
			if (!f.getVariables().contains(nodeId)) {
				remainingFactors.add(f);
			}
			else {
				dependentFactors.add(f);
			}
		}

		// we compute the product of the dependent factors
		DoubleFactor productDependentFactors = pointwiseProduct(dependentFactors);

		// we sum out the dependent factors
		DoubleFactor sumDependentFactors = sumOutDependent(nodeId, productDependentFactors);

		if (!sumDependentFactors.isEmpty()) {
			remainingFactors.add(sumDependentFactors);
		}

		return remainingFactors;
	}



	/**
	 * Sums out the variable from the given factor, and returns the result
	 * 
	 * @param node the Bayesian node corresponding to the variable
	 * @param factor the factor to sum out
	 * @return the summed out factor
	 */
	private DoubleFactor sumOutDependent(String nodeId, DoubleFactor factor) {

		// create the new factor
		DoubleFactor sumFactor = new DoubleFactor();	

		for (Assignment a : factor.getValues()) {
			Assignment reducedA = new Assignment(a);
			reducedA.removePair(nodeId);

			double sumProbIncrement = factor.getProbEntry(a);
			double sumUtilityIncrement = factor.getProbEntry(a) * factor.getUtilityEntry(a);

			sumFactor.incrementEntry(reducedA, sumProbIncrement, sumUtilityIncrement);
		}

		for (Assignment a : sumFactor.getValues()) {
			sumFactor.addEntry(a, sumFactor.getProbEntry(a), 
					sumFactor.getUtilityEntry(a) / sumFactor.getProbEntry(a));
		}

		return sumFactor;
	}



	/**
	 * Computes the pointwise matrix product of the list of factors
	 * 
	 * @param factors the factors
	 * @return the pointwise product of the factors
	 */
	private DoubleFactor pointwiseProduct (List<DoubleFactor> factors) {

		if (factors.size() == 1) {
			return factors.get(0);
		}

		DoubleFactor factor = new DoubleFactor();

		factor.addEntry(new Assignment(), 1.0f, 0.0f);

		for (DoubleFactor f: factors) {

			DoubleFactor tempFactor = new DoubleFactor();

			for (Assignment a : f.getValues()) {

				double probVal = f.getProbEntry(a);
				double utilityVal = f.getUtilityEntry(a);

				for (Assignment b: factor.getValues()) {
					if (b.consistentWith(a)) {
						double productProb = probVal * factor.getProbEntry(b);
						double sumUtility = utilityVal + factor.getUtilityEntry(b);

						tempFactor.addEntry(new Assignment(a,b), productProb, sumUtility);
					}
				}
			}
			factor = tempFactor;
		}

		return factor;
	}

	/**
	 * Creates a new factor given the probability distribution defined in the Bayesian
	 * node, and the evidence (which needs to be matched)
	 * 
	 * @param node the Bayesian node 
	 * @param evidence the evidence
	 * @return the factor for the node
	 */
	private DoubleFactor makeFactor(BNode node, Assignment evidence) {

		DoubleFactor factor = new DoubleFactor();

		// generates all possible assignments for the node content
		Map<Assignment,Double> flatTable = node.getFactor();
		for (Assignment a: flatTable.keySet()) {

			// verify that the assignment is consistent with the evidence
			if (a.consistentWith(evidence)) {
				// adding a new entry to the factor
				Assignment a2 = new Assignment(a);
				a2.removePairs(evidence.getVariables());

				if (node instanceof ChanceNode || node instanceof ActionNode) {
					factor.addEntry(a2, flatTable.get(a), 0.0f);
				}
				else if (node instanceof UtilityNode) {
					factor.addEntry(a2, 1.0f, flatTable.get(a));
				}
			}
		}

		return factor;
	}



	/**
	 * In case of overlap between the query variables and the evidence (this happens
	 * when a variable specified in the evidence also appears in the query), extends 
	 * the distribution to add the evidence assignment pairs.
	 * 
	 * @param query the query
	 * @param distribution the computed distribution
	 * @return the extended distribution
	 */
	private SimpleTable addEvidencePairs(Query query, Map<Assignment,Double> probDistrib) {

		SimpleTable table = new SimpleTable();
		//	table.addRows(probDistrib);

		// first, check if there is an overlap between the query variables and
		// the evidence variables
		TreeMap<String,Set<Value>> valuesToAdd = new TreeMap<String,Set<Value>>();
		for (String queryVar : query.getQueryVars()) {
			if (query.getEvidence().getPairs().containsKey(queryVar)) {
				valuesToAdd.put(queryVar, query.getNetwork().getNode(queryVar).getValues());
			}
		}

		Set<Assignment> possibleExtensions = CombinatoricsUtils.getAllCombinations(valuesToAdd);
		for (Assignment a : probDistrib.keySet()) {
			for (Assignment b: possibleExtensions) {

				// if the assignment b agrees with the evidence, reuse the probability value
				if (query.getEvidence().contains(b)) {
					table.addRow(new Assignment(a, b), probDistrib.get(a));
				}

				// else, set the probability value to 0.0f
				else {
					table.addRow(new Assignment(a, b), 0.0f);				
				}
			}
		}

		return table;
	}


	/**
	 * Special handling for a special case of utility query where there is absolutely
	 * no query variables (no actions).  In this case, we must pick up a variable in
	 * the network, perform the inference with it, and then sum it out.
	 * 
	 * @param query the utility query without the query variables
	 * @return the resulting distribution couple
	 * @throws DialException
	 */
	private DistributionCouple queryWithoutVars(UtilQuery query) throws DialException {
		List<BNode> nodes = query.getFilteredSortedNodes();
		SimpleTable probDistrib = new SimpleTable();
		probDistrib.addRow(new Assignment(), 1.0);
		UtilityTable utilDistrib = new UtilityTable();
		if (!nodes.isEmpty()) {
			String rootNode = nodes.get(nodes.size()-1).getId();
			Query query2 = new UtilQuery(query.getNetwork(), Arrays.asList(rootNode), query.getEvidence());
			DistributionCouple couple = queryJoint(query2);
			UtilityTable utable = couple.getUtilityDistrib();
			ProbDistribution distrib = couple.getProbDistrib();
			double utilValue = 0;
			for (Assignment a : utable.getTable().keySet()) {
				utilValue += utable.getUtility(a) * distrib.toDiscrete().getProb(new Assignment(), a);
			}
			utilDistrib.setUtility(new Assignment(), utilValue);
		}
		else {
			utilDistrib.setUtility(new Assignment(), 0.0);
		}
		return new DistributionCouple(probDistrib, utilDistrib);
	}


	// ===================================
	//  NETWORK REDUCTION METHODS
	// ===================================


	/**
	 * Reduces the Bayesian network by retaining only the query variables and
	 * marginalising out the rest.
	 * 
	 * <p>NB: conditional variables in the query are ignored.
	 * 
	 * @param query the query containing the network to reduce, the variables 
	 *        to retain, and possible evidence.
	 * @return a reduced Bayesian network
	 * @throws DialException if the reduction operation failed
	 */
	public BNetwork reduceNetwork(ReductionQuery query) throws DialException {
		
		// first, create the new network, without any distribution in the node
		BNetwork reduced = query.getNetwork().getReducedCopy(query.getQueryVars());
		
		// we can simplify the query if some nodes remain identical
		Set<String> identicalNodes = query.getNetwork().getIdenticalNodes(reduced, query.getEvidence());
		for (String nodeId : identicalNodes) {
			ChanceNode originalNode = query.getNetwork().getChanceNode(nodeId);
			reduced.getChanceNode(nodeId).setDistrib(originalNode.getDistrib());
			query.removeQueryVar(nodeId);
		}
		
		// create the factors associated with the query variables
		DoubleFactor queryFactor = createQueryFactor(query);

		// finally, sets the distribution for the nodes to retain, according
		// to the factors generated via V.E.
		for (ChanceNode node: reduced.getChanceNodes()) {	
			DoubleFactor factor = getRelevantFactor(queryFactor, node);
			DiscreteProbDistribution distrib = createProbDistribution(factor, node.getId());	
			node.setDistrib(distrib);
		}

		return reduced;
	}
	
	


	/**
	 * Returns the factor associated with the probability/utility distribution for the
	 * given node in the Bayesian network.  If the factor encode more than the needed 
	 * distribution, the surplus variables are summed out.
	 * 
	 * @param factors the collection of factors in which to search
	 * @param node the node in the Bayesian Network
	 * @return the relevant factor associated with the node
	 * @throws DialException if not relevant factor could be found
	 */
	private DoubleFactor getRelevantFactor (DoubleFactor fullFactor, 
			BNode node) throws DialException {

		// summing out unrelated variables
		DoubleFactor factor = fullFactor.copy();
		for (String otherVar : new ArrayList<String>(factor.getVariables())) {
			if (!otherVar.equals(node.getId()) 
					&& ! node.getInputNodeIds().contains(otherVar)) {
				factor = sumOut(otherVar, Arrays.asList(factor)).get(0);
			}
		}

		return factor;
	}



	/**
	 * Creates the probability distribution for the given variable, as described 
	 * by the factor.  The distribution is normalised, and encoded as a table.
	 * 
	 * @param factor the factor 
	 * @param variable the variable
	 * @return the resulting probability distribution
	 */
	private DiscreteProbDistribution createProbDistribution (DoubleFactor factor, 
			String variable) {

		// if the factor does not have dependencies, create a simple table
		if (factor.getVariables().size() == 1) {
			SimpleTable table = new SimpleTable();
			factor.normalise();
			for (Assignment a : factor.getMatrix().keySet()) {
				table.addRow(a, factor.getProbEntry(a));
			}
			return table;
		}

		// else, create a full probability table
		else {
			DiscreteProbabilityTable table = new DiscreteProbabilityTable();
			Set<String> depVariables = new HashSet<String>(factor.getVariables());
			depVariables.remove(variable);

			factor.normalise(depVariables);
			for (Assignment a : factor.getMatrix().keySet()) {
				Assignment condition = a.getTrimmed(depVariables);
				Assignment head = a.getTrimmedInverse(depVariables);
				table.addRow(condition, head, factor.getProbEntry(a));
			}
			return table;
		}
	}


	/**
	 * Creates the utility distribution for the given variable, as described 
	 * by the factor.  The distribution is encoded as a table.
	 * 
	 * @param factor the factor 
	 * @param variable the variable
	 * @return the resulting utility distribution
	 */
	private UtilityDistribution createUtilityDistribution (DoubleFactor factor, 
			String variable) {
		UtilityTable table = new UtilityTable();
		for (Assignment a : factor.getMatrix().keySet()) {
			table.addUtility(a, factor.getUtilityEntry(a));
		}
		return table;

	}

}