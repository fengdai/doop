package org.clyze.doop.utils

import groovy.transform.Canonical
import org.clyze.doop.core.DoopAnalysis

class XTractor {
	static DoopAnalysis analysis
	static File souffleOut

	static File delveOut
	static List delveOutputRels = []

	static def arrayMeta = [:].withDefault { [] }
	static Map<String, Set<String>> varAliases = [:].withDefault { [] as Set }

	static void run(DoopAnalysis analysis) {
		this.analysis = analysis
		souffleOut = new File(analysis.database, "xtractor-out.dl")
		delveOut = new File(analysis.database, "xtractor-out.jl")
		souffleOut.text = ""
		delveOut.text = """using DelveSDK
conn = LocalConnection(dbname=:test)
create_database(conn; overwrite=true)

"""
		new File(analysis.database, "Flows_EXT.csv").eachLine {
			def (String from, String to) = it.split("\t")
			varAliases[from] << to
		}

		arrays()
		conditions()
//		schema()

		println "Results in... $souffleOut and $delveOut\n"
	}

	static def arrays() {
		def mainArrays = []
		Map<String, Integer> relNameToVariant = [:]
		souffleOut << ".decl array_META(relation:symbol, name:symbol, types:symbol, dimensions:number)\n"
		new File(analysis.database, "MainArrayVar.csv").eachLine {
			def (String array, String name, String types) = it.split("\t")
			def dimensions = types.count("[]")
			def relName = "${name.split("#").first()}" as String
			def variant = relNameToVariant[relName]
			if (variant == null)
				relNameToVariant[relName] = 0
			else {
				relNameToVariant[relName] = variant + 1
				relName += "_$variant"
			}
			def dims = (1..dimensions).collect { "i$it:number" }.join(", ")
			def dimSizes = (1..dimensions).collect { "dim$it:number" }.join(", ")
			souffleOut << ".decl $relName($dims, value:symbol)\n"
			souffleOut << ".decl ${relName}_Init($dims, value:symbol)\n"
			souffleOut << ".decl ${relName}_DimSizes($dims)\n"
			souffleOut << ".decl ${relName}_Provided($dims, value:symbol)\n"
			souffleOut << ".input ${relName}_Provided\n"
			souffleOut << ".decl ${relName}_Missing($dims)\n"
			souffleOut << ".output ${relName}_Missing\n"
			souffleOut << "array_META(\"$relName\", \"$array\", \"$types\", $dimensions).\n"
			delveOut << "load_edb(conn, :array_META, [(\"$relName\", \"$array\", \"$types\", $dimensions)])\n"
			def metaInfo = [relName, name, types, dimensions]
			varAliases[array].each { arrayMeta[it] = metaInfo }
			mainArrays << array
		}

		souffleOut << "// Array Dimensions\n"
		delveOut << "\n"
		def arrayDims = [:].withDefault { [:] }
		new File(analysis.database, "ArrayDims.csv").eachLine {
			def (String array, pos, size) = it.split("\t")
			arrayDims[array][pos as int] = size
		}
		mainArrays.each { array ->
			def (String relName, name, types, int dimensions) = arrayMeta[array]
			def sizes = arrayDims[array]
			def allSizes = (1..dimensions).collect { sizes[it-1] ?: -1 }.join(", ")
			souffleOut << "${relName}_DimSizes($allSizes).\n"
			delveOut << "load_edb(conn, :${relName}_DimSizes, [($allSizes)])\n"
		}

		souffleOut << "// Array Initialization\n"
		delveOut << "\n"
		def delvePendingRules = [:].withDefault { [] }
		new File(analysis.database, "OUT_ArrayInitialized.csv").eachLine {
			def (String array, String value) = it.split("\t")
			if (!value.isNumber()) throw new RuntimeException("Invalid AP?")
			def (String relName, name, String types, int dimensions) = arrayMeta[array]
			def allZero = (1..dimensions).collect { 0 }.join(", ")
			def indexes = (1..dimensions).collect {"i$it" }.join(", ")
			def headIndexes2 = (1..dimensions).collect {"i$it" }.join(", ")
			souffleOut << "$relName($indexes, val) :- ${relName}_Init($indexes, val).\n"
			delvePendingRules[relName] << "def $relName($indexes, val) = ${relName}_Init($indexes, val)"
			souffleOut << "${relName}_Init($allZero, ${fixVal(value, types)}).\n"
			delveOut << "install_source(conn, \"${relName}_Init\", \"\"\"\n"
			delveOut << "def ${relName}_Init = {($allZero, ${fixVal(value, types)})}\n"
			dimensions.times {index ->
				def headIndexes = (1..dimensions).collect {it-1 == index ? "i$it + 1" : "i$it" }.join(", ")
				def size = arrayDims[array][index] as int
				souffleOut << "${relName}_Init($headIndexes, val) :- ${relName}_Init($indexes, val), i${index+1} < ${size-1}.\n"
				def indexes2 = (1..dimensions).collect {it-1 == index ? "x" : "i$it" }.join(", ")
				delveOut << "def ${relName}_Init($headIndexes2, val) = exists(x: ${relName}_Init($indexes2, val) and i${index+1} = x + 1 and x < ${size-1})\n"
			}
			delveOut << "\"\"\")\n"
		}

		souffleOut << "// Array (External) Values\n"
		delveOut << "\n"
		def arraysWithAccess = [] as Set
		def delveProvidedRels = [:]
		def delveProvidedRelsTypes = [:]
		new File(analysis.database, "OUT_ArrayWrite.csv").eachLine {String rawAP ->
			def parts = rawAP.split("@")
			if (parts.any { it.endsWith("#?") }) return
			def array = parts.first()
			def (String relName, name, String types, int dimensions) = arrayMeta[array]
			def rest = parts.drop(1)
			def last = fixVal(rest.last(), types)
			def indexes = (rest.dropRight(1) + [last]).toList().withIndex()
					.collect { t, int i -> t == "?" ? "i$i" : t}.join(", ")
			souffleOut << (rawAP.contains("@?") ?
					"$relName($indexes) :- ${relName}_Provided($indexes).\n" :
					"$relName($indexes).\n")

			def indexes2 = (1..dimensions+1).collect {"i$it" }.join(", ")
			def values = (rest.dropRight(1) + [last]).toList().withIndex()
					.collect { t, int i -> t == "?" ? null : "i${i+1} = $t"}.grep().join(", ")
			delvePendingRules[relName] << "def $relName($indexes2) = ${relName}_Provided($indexes2), $values"
			def csv = (1..dimensions+1).collect { "${relName}_Provided_csv(pos, :c$it, i$it)" }.join(", ")
			delveProvidedRels["${relName}_Provided"] = "def ${relName}_Provided($indexes2) = exists(pos: $csv)"
			delveProvidedRelsTypes["${relName}_Provided"] = ((1..dimensions).collect {"Int" } + "String").join(",")
			arraysWithAccess << array
		}

		delveProvidedRels.each { relName, rule ->
			delveOut << "load_csv(conn, :${relName}_csv; schema=FileSchema(Tuple{${delveProvidedRelsTypes[relName]}}), syntax=CSVFileSyntax(delim='\\t'), path=\"./${relName}.csv\")\n"
			delveOut << "install_source(conn, \"$relName\", \"\"\"$rule\"\"\")\n\n"
			delveOutputRels << ":$relName"
		}
		delvePendingRules.each { relName, rules ->
			delveOut << "install_source(conn, \"$relName\", \"\"\"\n${rules.join("\n")}\n\"\"\")\n"
		}

		souffleOut << "// Array External Values Sanity\n"
		arraysWithAccess.each { array ->
			def (String relName, name, String types, int dimensions) = arrayMeta[array]
			def dims = (1..dimensions+1).collect { "i$it" }.join(", ")
			souffleOut << "${relName}_Missing($dims) :-\n\t${relName}_Provided($dims),\n\t!$relName($dims).\n"
			delveOut << "install_source(conn, \"${relName}_Missing\", \"\"\"\ndef ${relName}_Missing($dims) = ${relName}_Provided($dims) and not $relName($dims)\n\"\"\")\n"
			delveOutputRels += [":$relName", ":${relName}_Missing"]
		}
	}

	static def conditions() {
		souffleOut << "\n// Rules\n"
		delveOut << "\n"
		Map<String, Expr> ifReturnsExpr = [:]
		new File(analysis.database, "OUT_IfReturnsStr.csv").eachLine {
			def (String stmt, String rawAP) = it.split("\t")
			ifReturnsExpr[stmt] = ap(rawAP, "ret")
		}
		def methodsWithRules = []
		def ruleDecls = []
		def delveRules = [:].withDefault { [] }
		new File(analysis.database, "OUT_IfGroupConditionStr.csv").eachLine {
			def (String stmt, String methodName, String complexCond) = it.split("\t")
			def conditions = complexCond.split(" AND ")
			def res = [new RelExpr("ret", methodName)] as List<Expr>
			conditions.eachWithIndex { cond, index ->
				def (String left, String op, String right) = cond.split("\\|")
				def l = ap(left, "tmp1$index")
				def r = ap(right, "tmp2$index")
				res += [l, r, new CompExpr(null, l.tempVar, op == "==" ? "=" : op, r.tempVar)]
			}
			res = exprOpt(res + ifReturnsExpr[stmt])
			if (methodName !in methodsWithRules)
				ruleDecls << ".decl $methodName(value:symbol)\n.output $methodName"
			def head = res.first()
			def body = res.drop(1)
			souffleOut << "\n${head.str()} :-\n\t"
			souffleOut << "${body.collect { it.str() }.join(",\n\t")}.\n"
			def onlyBodyVars = (body.collect { it.vars }.flatten() as Set) - head.vars
			def mainBodyStr = body.collect { it.str() }.join(", ")
			delveRules[methodName] << "def ${head.str()} = ${onlyBodyVars ? "exists(${onlyBodyVars.join(", ")}: $mainBodyStr)" : mainBodyStr}"
			methodsWithRules << methodName
		}
		new File(analysis.database, "OUT_NoIfReturnsStr.csv").eachLine {
			def (String methodName, String retType, String rawAP) = it.split("\t")
			if (methodName !in methodsWithRules) return
			def res = [new RelExpr("ret", methodName + "_Def"),
			           new RelExpr("_", "!" + methodName),
					   ap(fixVal(rawAP, retType), "ret")]
			res = exprOpt(res)
			ruleDecls << ".decl ${methodName}_Def(value:symbol)\n.output ${methodName}_Def"
			def head = res.first()
			def body = res.drop(1)
			souffleOut << "\n${head.str()} :-\n\t"
			souffleOut << "${body.collect { it.str() }.join(",\n\t")}.\n"
			def mainBodyStr = body.collect { it.str() }.join(", ").replaceAll("!", "not ")
			delveRules["${methodName}_Def"] << "def ${head.str()} = $mainBodyStr"
		}
		souffleOut << "\n"
		delveOut << "\n"
		ruleDecls.each { souffleOut << "$it\n" }
		delveRules.each { rel, rules ->
			delveOut << "install_source(conn, \"$rel\", \"\"\"\n"
			rules.each { delveOut << "$it\n"}
			delveOut << "\"\"\")\n"
			delveOutputRels << ":$rel"
		}

		delveOut << "\nquery(conn; outputs=[${delveOutputRels.join(", ")}])\n"
	}

	static def schema() {
		Map<String, List<String[]>> classInfo = [:].withDefault { [] }
		new File(analysis.database, "OUT_ClassInfo.csv").eachLine { line ->
			def (klass, kind, field, fieldType) = line.split("\t")
			classInfo[klass] << [kind, field, fieldType]
		}

		def dlTypes = [] as Set
		def dlDecls = []
		def dlInputs = []
		def m = { def type ->
			switch (type) {
				case "int": return "number"
				case "java.lang.String": return "symbol"
				case "java.lang.String[]":
					dlTypes << ".type AR__symbol = [ head:symbol, rest:AR__symbol ]"
					return "AR__symbol"
				case ~/.*\[\]/:
					def t = type[0..-3]
					dlTypes << ".type AR_$t = [ head:$t, rest:AR_$t ]"
					return "AR_$t"
				default: return type
			}
		}

		classInfo.each { klass, relations ->
			dlTypes << ".type $klass"
			relations.each {
				def (kind, field, fieldType) = it
				String name
				switch (kind) {
					case "r":
						name = "${klass}_${field}"
						dlDecls << ".decl $name(this:$klass, $field:${m(fieldType)})"
						break
					case "R":
						name = "${klass}_CL_${field}"
						dlDecls << ".decl $name($field:${m(fieldType)})"
						break
					case "r[]":
						name = "${klass}_AR_${field}"
						dlDecls << ".decl $name(this:$klass, index:number, elem:${m(fieldType)})"
						break
					case "R[]":
						name = "${klass}_CL_AR_${field}"
						dlDecls << ".decl $name($field:${m(fieldType)})"
						break
					default:
						println "ERROR"
				}
				dlInputs << ".input $name"
			}
			def allFields = relations.findAll { it[0] == "r" }.collect { "${it[1]}:${m(it[2])}" }.join(", ")
			dlDecls << ".decl ${klass}_ALL(this:symbol, $allFields)"
			dlInputs << ".input ${klass}_ALL"
		}
//		outFile << "\n"
//		dlTypes.each { outFile << "$it\n" }
//		dlDecls.each { outFile << "$it\n" }
//		dlInputs.each { outFile << "$it\n" }
	}

	static def ap(String rawAP, String tempVar) {
		def parts = rawAP.split("@")
		parts.length == 1 ?
				new CompExpr(tempVar, tempVar, "=", parts[0]) :
				new ArrayExpr(tempVar, parts[0], parts.drop(1).collect { cleanVal(it) })
	}

	static def cleanVal(String value) {
		value.isNumber() ? value : value.split("/").last().split('_\\$\\$A_').first()
	}

	static def fixVal(String value, String types) {
		(types.startsWith("char") && value.isNumber()) ? "\"${value.toInteger() as char}\"" : value
	}

	static List<Expr> exprOpt(List<Expr> exprs) {
		def hasCharValues = false
		def dupsMap = [:].withDefault { [] }
		exprs.each {expr ->
			if (expr !instanceof ArrayExpr || dupsMap.containsKey(expr)) return
			exprs.eachWithIndex{ Expr e, int i ->
				if (expr !== e && expr.eq(e)) dupsMap[expr] += i
			}
			if (expr instanceof ArrayExpr) {
				def (String relName, name, String types, int dimensions) = arrayMeta[expr.array]
				hasCharValues = hasCharValues || types.startsWith("char")
			}
		}
		dupsMap.findAll{it.value.size() }.each { Expr e, List<Integer> dups ->
			dups.each { i ->
				def orig = exprs[i].tempVar
				exprs.each {it?.replace(orig, e.tempVar) }
				exprs[i] = null
			}
		}
		exprs.eachWithIndex { Expr e, int i ->
			if (e instanceof ArrayExpr) {
				e.array = arrayMeta[e.array].first()
			} else if (e instanceof CompExpr) {
				e.left = fixVal(e.left, hasCharValues ? "char" : "")
				e.right = fixVal(e.right, hasCharValues ? "char" : "")
			}
			if (e == null || e !instanceof CompExpr || e.op != "=") return
			def (orig, repl) = e.left.isNumber() ? [e.right, e.left] : [e.left, e.right]
			exprs.each {it?.replace(orig, repl) }
			exprs[i] = null
		}
		return exprs.grep()
	}
}

@Canonical
abstract class Expr {
	String tempVar

	abstract String str()

	boolean eq(Expr o) { false }

	void replace(String orig, String repl) { if (tempVar == orig) tempVar = repl }

	Set<String> getVars() { [] as Set }
}

@Canonical(includeSuperProperties = true)
class RelExpr extends Expr {
	String relName

	String str() { "$relName($tempVar)" }

	Set<String> getVars() { [tempVar] as Set }
}

@Canonical(includeSuperProperties = true)
class ArrayExpr extends Expr {
	String array
	List<String> indexes

	String str() { "$array(${indexes.join(", ")}, $tempVar)" }

	boolean eq(Expr o) {
		if (o !instanceof ArrayExpr || array != o.array) return false
		indexes == o.indexes
	}

	Set<String> getVars() {
		(indexes.findAll { !it.isNumber() } + [tempVar]) as Set
	}
}

@Canonical(includeSuperProperties = true)
class CompExpr extends Expr {
	String left
	String op
	String right

	String str() { "$left $op $right" }

	void replace(String orig, String repl) {
		if (left == orig) left = repl
		if (right == orig) right = repl
	}
}