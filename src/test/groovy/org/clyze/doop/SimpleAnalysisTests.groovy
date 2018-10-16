package org.clyze.doop

import org.clyze.analysis.Analysis
import org.clyze.doop.core.Doop
import spock.lang.Specification
import spock.lang.Unroll
import static org.clyze.doop.TestUtils.*

/**
 * Test small programs from the server-analysis-tests repo.
 */
class SimpleAnalysisTests extends ServerAnalysisTests {

	// @spock.lang.Ignore
	@Unroll
	def "Server analysis test 012 (interface fields)"() {
		when:
		analyzeTest("012-interface-fields", ["--Xextra-logic", "${Doop.souffleAddonsPath}/testing/test-exports.dl"])

		then:
		staticFieldPointsTo(analysis, '<Y: Y fooooooooo>', '<Y: void <clinit>()>/new Y$1/0')
	}

	// @spock.lang.Ignore
	@Unroll
	def "Server analysis test 006 (hello world) / test additional command line options"() {
		when:
		analyzeTest("006-hello-world", ["--cfg", "--coarse-grained-allocation-sites", "--cs-library", "--sanity", "--Xextra-metrics", "--dont-report-phantoms", "--platform", "java_8", "--thorough-fact-gen"])

		then:
		relationHasExactSize(analysis, "VarHasNoType", 0)
		relationHasExactSize(analysis, "TypeIsNotConcreteType", 0)
		relationHasExactSize(analysis, "InstructionIsNotConcreteInstruction", 0)
		relationHasExactSize(analysis, "ValueHasNoType", 0)
		relationHasExactSize(analysis, "ValueHasNoDeclaringType", 0)
		relationHasExactSize(analysis, "NotReachableVarPointsTo", 0)
		relationHasExactSize(analysis, "VarPointsToWronglyTypedValue", 0)
		relationHasExactSize(analysis, "VarPointsToMergedHeap", 0)
		relationHasExactSize(analysis, "HeapAllocationHasNoType", 0)
		relationHasExactSize(analysis, "ValueIsNeitherHeapNorNonHeap", 0)
		relationHasExactSize(analysis, "ClassTypeIsInterfaceType", 0)
		relationHasExactSize(analysis, "PrimitiveTypeIsReferenceType", 0)
	}
}
