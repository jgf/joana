/**
 * This file is part of the Joana IFC project. It is developed at the
 * Programming Paradigms Group of the Karlsruhe Institute of Technology.
 *
 * For further details on licensing please read the information at
 * http://joana.ipd.kit.edu or contact the authors.
 */

package edu.kit.joana.ifc.sdg.qifc.nildumu;

import edu.kit.joana.api.IFCAnalysis;
import com.ibm.wala.ipa.callgraph.CallGraph;
import edu.kit.joana.wala.core.SDGBuilder;

public class BuildResult {
	public final SDGBuilder builder;

	public final CallGraph cg;

	public final IFCAnalysis analysis;

	public BuildResult(SDGBuilder builder, IFCAnalysis analysis, CallGraph cg) {
		this.builder = builder;
		this.analysis = analysis;
		this.cg = cg;
	}

}
