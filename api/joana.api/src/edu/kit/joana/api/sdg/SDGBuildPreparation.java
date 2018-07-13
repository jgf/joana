/**
 * This file is part of the Joana IFC project. It is developed at the
 * Programming Paradigms Group of the Karlsruhe Institute of Technology.
 *
 * For further details on licensing please read the information at
 * http://joana.ipd.kit.edu or contact the authors.
 */
package edu.kit.joana.api.sdg;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import com.ibm.wala.cfg.exc.intra.MethodState;
import com.ibm.wala.classLoader.BinaryDirectoryTreeModule;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.classLoader.Module;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.ContextSelector;
import com.ibm.wala.ipa.callgraph.pruned.ApplicationLoaderPolicy;
import com.ibm.wala.ipa.callgraph.pruned.PruningPolicy;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.MonitorUtil.IProgressMonitor;
import com.ibm.wala.util.collections.Pair;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.util.config.FileOfClasses;
import com.ibm.wala.util.config.SetOfClasses;
import com.ibm.wala.util.graph.GraphIntegrity.UnsoundGraphException;
import com.ibm.wala.util.strings.StringStuff;

import edu.kit.joana.ifc.sdg.graph.SDG;
import edu.kit.joana.ifc.sdg.graph.SDGSerializer;
import edu.kit.joana.util.LogUtil;
import edu.kit.joana.util.Stubs;
import edu.kit.joana.util.io.IOFactory;
import edu.kit.joana.wala.core.CGConsumer;
import edu.kit.joana.wala.core.ExternalCallCheck;
import edu.kit.joana.wala.core.NullProgressMonitor;
import edu.kit.joana.wala.core.SDGBuildArtifacts;
import edu.kit.joana.wala.core.SDGBuilder;
import edu.kit.joana.wala.core.SDGBuilder.ControlDependenceVariant;
import edu.kit.joana.wala.core.SDGBuilder.DynamicDispatchHandling;
import edu.kit.joana.wala.core.SDGBuilder.ExceptionAnalysis;
import edu.kit.joana.wala.core.SDGBuilder.FieldPropagation;
import edu.kit.joana.wala.core.SDGBuilder.PointsToPrecision;
import edu.kit.joana.wala.core.SDGBuilder.StaticInitializationTreatment;
import edu.kit.joana.wala.core.params.objgraph.SideEffectDetectorConfig;
import edu.kit.joana.wala.flowless.pointsto.AliasGraph.MayAliasGraph;
import edu.kit.joana.wala.flowless.spec.java.ast.MethodInfo;
import edu.kit.joana.wala.summary.SummaryComputationType;
import edu.kit.joana.wala.util.WALAUtils;
import edu.kit.joana.wala.util.WriteGraphToDot;
import edu.kit.joana.wala.util.pointsto.ObjSensZeroXCFABuilder;

public final class SDGBuildPreparation {

	private SDGBuildPreparation() {
		throw new UnsupportedOperationException();
	}

	public final static int DEFAULT_PRUNE_CG = 2;
	public final static int DO_NOT_PRUNE_CG = SDGBuilder.DO_NOT_PRUNE;

	public final static String STD_CLASS_PATH = "bin/";

	public final static ExceptionAnalysis DEFAULT_EXCEPTION_ANALYSIS = ExceptionAnalysis.INTRAPROC;

	public final static boolean DEFAULT_ACCESS_PATH = false;

	public static ClassHierarchy computeClassHierarchy(PrintStream out, Config cfg) throws IOException,
			ClassHierarchyException {
		AnalysisScope scope = setUpAnalysisScope(out, cfg);
		// Klassenhierarchie berechnen
		return ClassHierarchyFactory.make(scope);
	}

	public static List<String> searchMainMethods(PrintStream out, Config cfg) throws IOException,
			ClassHierarchyException {
		final List<String> result = new LinkedList<String>();
		out.println("Searching for main methods in '" + cfg.classpath + "'...");
		ClassHierarchy cha = computeClassHierarchy(out, cfg);
		for (final IClass cls : cha) {
			if (!cls.isInterface() && !cls.isAbstract()
					&& cls.getClassLoader().getName().equals(AnalysisScope.APPLICATION)) {
				for (final IMethod m : cls.getDeclaredMethods()) {
					if (m.isStatic() && "main([Ljava/lang/String;)V".equals(m.getSelector().toString())) {
						out.println("\tfound '" + m.getSignature() + "'");
						result.add(m.getSignature());
					}
				}
			}
		}

		out.println("done.");

		return result;
	}

	public static void run(PrintStream out, Config cfg) throws IOException, ClassHierarchyException,
			UnsoundGraphException, CancelException {
		final SDG sdg = compute(out, cfg);

		if (sdg != null) {
			out.print("Writing SDG to disk... ");
			final String fileName = cfg.outputDir + WriteGraphToDot.sanitizeFileName(sdg.getName()) + ".pdg";
			final File file = new File(fileName);
			out.print("(" + file.getAbsolutePath() + ") ");
			PrintWriter pw = new PrintWriter(IOFactory.createUTF8PrintStream(new FileOutputStream(file)));
			SDGSerializer.toPDGFormat(sdg, pw);
			out.println("done.");
		}
	}

	public static AnalysisScope setUpAnalysisScope(final PrintStream out, final Config cfg) throws IOException {
		// Fuegt die normale Java Bibliothek zum Scope hinzu

		// deactivates WALA synthetic methods if cfg.nativesXML != null
		com.ibm.wala.ipa.callgraph.impl.Util.setNativeSpec(cfg.stubs.getNativeSpecFile());

		AnalysisScope scope;
		// if use stubs
		assert cfg.stubs != null;
		final String[] stubPaths = cfg.stubs.getPaths();
		if (stubPaths.length > 0) {
			assert cfg.stubs != Stubs.NO_STUBS;
			scope = AnalysisScope.createJavaAnalysisScope();
			for (final String stub : stubPaths) {
				final Module stubs = WALAUtils.findJarModule(out, stub);
				scope.addToScope(ClassLoaderReference.Primordial, stubs);
			}
		} else {
			assert cfg.stubs == Stubs.NO_STUBS;
			scope = AnalysisScopeReader.makePrimordialScope(null);
		}

		// Nimmt unnoetige Klassen raus

		SetOfClasses exclusions = new FileOfClasses(new ByteArrayInputStream(IOFactory.createUTF8Bytes(cfg.exclusions)));
		scope.setExclusions(exclusions);

		ClassLoaderReference loader = scope.getLoader(AnalysisScope.APPLICATION);
		AnalysisScopeReader.addClassPathToScope(cfg.classpath, scope, loader, cfg.classpathAddEntriesFromMANIFEST);
		if (cfg.thirdPartyLibPath != null) {
			ClassLoaderReference extLoader = scope.getLoader(AnalysisScope.EXTENSION);
			AnalysisScopeReader.addClassPathToScope(cfg.thirdPartyLibPath, scope, extLoader,
					cfg.classpathAddEntriesFromMANIFEST);
		}
		return scope;
	}

	/**
	 * This method constructs a minimal analysis scope with {@link SDGBuildPreparation.STD_EXCLUSION_REG_EXP standard
	 * exclusions} and JRE1.4 stubs.
	 * 
	 * @param appClassPaths
	 *            application class path - may contain multiple items, separated by ';'
	 * @return a minimal analysis scope with JRE1.4 stubs
	 * @throws IOException
	 */
	public static AnalysisScope makeMinimalScope(String appClassPaths) throws IOException {
		return makeMinimalScope(appClassPaths, Stubs.JRE_15, SDGBuilder.STD_EXCLUSION_REG_EXP);
	}

	/**
	 * This method constructs a minimal analysis scope. The user may decide which stubs and exclusions to use
	 * 
	 * @param appClassPaths
	 *            application class path - may contain multiple items, separated by ';'
	 * @param stubs
	 *            stubs to use
	 * @param exclusionsRegexp
	 *            exclusions to use -- for the expected format, see {@link SDGBuilder.STD_EXCLUSION_REG_EXP}
	 * @return a minimal analysis scope user chosen stubs and exclusions
	 * @throws IOException
	 */
	public static AnalysisScope makeMinimalScope(String appClassPaths, Stubs stubs, String exclusionsRegexp)
			throws IOException {
		AnalysisScope scope = AnalysisScope.createJavaAnalysisScope();
		String[] appClassPathParts = appClassPaths.split(";");
		for (int i = 0; i < appClassPathParts.length; i++) {
			String appClassPath = appClassPathParts[i];
			if (!appClassPath.endsWith(".jar")) {
				scope.addToScope(ClassLoaderReference.Application,
						new BinaryDirectoryTreeModule(new File(appClassPath)));
			} else {
				scope.addToScope(ClassLoaderReference.Application, WALAUtils.findJarModule(appClassPath));
			}
		}
		for (String stubsPath : stubs.getPaths()) {
			scope.addToScope(ClassLoaderReference.Primordial, WALAUtils.findJarModule(stubsPath));
		}
		scope.setExclusions(new FileOfClasses(new ByteArrayInputStream(exclusionsRegexp.getBytes())));
		return scope;
	}

	public static SDG compute(PrintStream out, Config cfg) throws ClassHierarchyException, IOException,
			UnsoundGraphException, CancelException {
		return compute(out, cfg, NullProgressMonitor.INSTANCE);
	}

	public static Pair<Long, SDGBuilder.SDGBuilderConfig> prepareBuild(PrintStream out, Config cfg,
			IProgressMonitor progress) throws IOException, ClassHierarchyException {
		if (!checkOrCreateOutputDir(cfg.outputDir)) {
			out.println("Could not access/create diretory '" + cfg.outputDir + "'");
			return null;
		}
		final long startTime = System.currentTimeMillis();

		out.print("Setting up analysis scope... ");

		AnalysisScope scope = setUpAnalysisScope(out, cfg);

		out.println("done.");

		out.print("Creating class hierarchy... ");

		// Klassenhierarchie berechnen
		ClassHierarchy cha = ClassHierarchyFactory.make(scope);

		out.println("(" + cha.getNumberOfClasses() + " classes) done.");

		if (cfg.extern != null) {
			cfg.extern.setClassHierarchy(cha);
		}

		if (cfg.extern != null) {
			cfg.extern.setClassHierarchy(cha);
		}
		IMethod m = null;
		List<IMethod> ms = prepareEntryMethods(out, cfg, cha);

		out.println("done.");

		AnalysisCache cache = new AnalysisCacheImpl();

		ExternalCallCheck chk;
		if (cfg.extern == null) {
			final boolean reflectionPossible = cha.lookupClass(TypeReference.JavaLangReflectMethod) != null
					&& cha.lookupClass(TypeReference.JavaLangReflectConstructor) != null;
			chk = new ExternalCallCheck() {
				@Override
				public boolean isCallToModule(SSAInvokeInstruction invk) {
					return false;
				}

				@Override
				public void registerAliasContext(SSAInvokeInstruction invk, int callNodeId, MayAliasGraph context) {
				}

				@Override
				public void setClassHierarchy(IClassHierarchy cha) {
				}

				@Override
				public MethodInfo checkForModuleMethod(IMethod im) {
					return null;
				}

				@Override
				public boolean resolveReflection() {
					return reflectionPossible;
				}
			};
		} else {
			chk = cfg.extern;
		}

		final SDGBuilder.SDGBuilderConfig scfg = new SDGBuilder.SDGBuilderConfig();
		scfg.nativeSpecClassLoader = cfg.stubs.getNativeSpecClassLoader();
		scfg.out = out;
		scfg.scope = scope;
		scfg.cache = cache;
		scfg.cha = cha;
		scfg.entry = m;
		scfg.entries = ms;
		scfg.ext = chk;
		scfg.immutableNoOut = SDGBuilder.IMMUTABLE_NO_OUT;
		scfg.immutableStubs = SDGBuilder.IMMUTABLE_STUBS;
		scfg.ignoreStaticFields = SDGBuilder.IGNORE_STATIC_FIELDS;
		scfg.exceptions = cfg.exceptions;
		scfg.defaultExceptionMethodState = cfg.defaultExceptionMethodState;
		scfg.accessPath = cfg.accessPath;
		scfg.sideEffects = cfg.sideEffects;
		scfg.prunecg = DEFAULT_PRUNE_CG;
		scfg.pruningPolicy = cfg.pruningPolicy;
		scfg.pts = cfg.pts;
		if (cfg.objSensFilter != null) {
			scfg.objSensFilter = cfg.objSensFilter;
		}
		scfg.staticInitializers = StaticInitializationTreatment.SIMPLE;
		scfg.fieldPropagation = cfg.fieldPropagation;
		scfg.debugManyGraphsDotOutput = cfg.debugManyGraphsDotOutput;
		scfg.computeInterference = cfg.computeInterference;
		scfg.computeSummary = cfg.computeSummaryEdges;
		scfg.summaryComputationType = cfg.summaryComputationType;
		scfg.computeAllocationSites = cfg.computeAllocationSites;
		scfg.cgConsumer = cfg.cgConsumer;
		scfg.additionalContextSelector = cfg.ctxSelector;
		scfg.dynDisp = cfg.ddisp;
		scfg.doParallel = cfg.isParallel;
		scfg.controlDependenceVariant = cfg.controlDependenceVariant;
		return Pair.make(startTime, scfg);
	}

	private static List<IMethod> prepareEntryMethods(PrintStream out, Config cfg, ClassHierarchy cha) {
	    List<IMethod> ms = null;
	    if (cfg.entryMethods != null && cfg.entryMethods.size() > 0) {
	      ms = new ArrayList<IMethod>();
	      for (String entryMethod : cfg.entryMethods) {
	        out.print("Setting up entrypoint " + entryMethod + "... ");

	        // Methode in der Klassenhierarchie suchen
	        final MethodReference mr = StringStuff.makeMethodReference(Language.JAVA, entryMethod);

	        IMethod meth = cha.resolveMethod(mr);
	        if (meth == null) {
	          fail("could not resolve " + mr);
	        }
	        ms.add(meth);
	      }
	    }
	    return ms;
	  }
	
	private static void postpareBuild(long startTime, PrintStream out) {
		out.println("\ndone.");
		final long endTime = System.currentTimeMillis();

		out.println("Time needed: " + (endTime - startTime) + "ms - Memory: "
				+ ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024))
				+ "M used.");
	}

	public static SDG compute(PrintStream out, Config cfg, IProgressMonitor progress) throws IOException,
			ClassHierarchyException, UnsoundGraphException, CancelException {
		Pair<Long, SDGBuilder.SDGBuilderConfig> p = prepareBuild(out, cfg, progress);
		long startTime = p.fst;
		SDGBuilder.SDGBuilderConfig scfg = p.snd;
		out.print("Building system dependence graph... ");
		final SDG sdg = SDGBuilder.build(scfg, progress);
		postpareBuild(startTime, out);
		// SDGVerifier.verify(sdg, false, true);

		return sdg;
	}

	public static Pair<SDG, SDGBuilder> computeAndKeepBuilder(PrintStream out, Config cfg, IProgressMonitor progress)
			throws UnsoundGraphException, CancelException, IOException, ClassHierarchyException {
		Pair<Long, SDGBuilder.SDGBuilderConfig> p = prepareBuild(out, cfg, progress);
		long startTime = p.fst;
		SDGBuilder.SDGBuilderConfig scfg = p.snd;
		out.print("Building system dependence graph... ");
		final Pair<SDG, SDGBuilder> ret = SDGBuilder.buildAndKeepBuilder(scfg, progress);
		postpareBuild(startTime, out);
		// SDGVerifier.verify(sdg, false, true);

		return ret;
	}

	public static Pair<SDG, SDGBuildArtifacts> computeAndKeepBuildArtifacts(PrintStream out, Config cfg,
			IProgressMonitor progress) throws UnsoundGraphException, CancelException, IOException,
			ClassHierarchyException {
		Pair<Long, SDGBuilder.SDGBuilderConfig> p = prepareBuild(out, cfg, progress);
		long startTime = p.fst;
		SDGBuilder.SDGBuilderConfig scfg = p.snd;
		out.print("Building system dependence graph... ");
		final Pair<SDG, SDGBuildArtifacts> ret = SDGBuilder.buildAndKeepBuildArtifacts(scfg, progress);
		postpareBuild(startTime, out);
		// SDGVerifier.verify(sdg, false, true);

		return ret;
	}

	public static SDGBuilder createBuilder(PrintStream out, Config cfg, IProgressMonitor progress)
			throws UnsoundGraphException, CancelException, ClassHierarchyException, IOException {
		Pair<Long, SDGBuilder.SDGBuilderConfig> p = prepareBuild(out, cfg, progress);
		return SDGBuilder.onlyCreate(p.snd);
	}

	public static boolean checkOrCreateOutputDir(String dir) {
		if (dir.endsWith(File.separator)) {
			dir = dir.substring(0, dir.length() - File.separator.length());
		}

		final File f = new File(dir);

		if (!f.exists()) {
			if (!f.mkdirs()) {
				return false;
			}
		}

		return f.canRead() && f.canWrite();
	}

	private static void fail(String msg) {
		throw new IllegalStateException(msg);
	}

	public static class Config {
		public String name;
		public String entryMethod;
		public List<String> entryMethods;
		public String classpath;
		public boolean classpathAddEntriesFromMANIFEST = true;
		public String thirdPartyLibPath;
		public String exclusions;
		public Stubs stubs;
		public String outputDir;
		public ExternalCallCheck extern;
		public PointsToPrecision pts;
		// only used iff pts is set to object sensitive. If null defaults to
		// "do object sensitive analysis for all methods"
		public ObjSensZeroXCFABuilder.MethodFilter objSensFilter = null;
		public ExceptionAnalysis exceptions;
		public MethodState defaultExceptionMethodState = null;
		public boolean accessPath;
		public boolean computeInterference = false;
		public boolean computeSummaryEdges = true;
		public SummaryComputationType summaryComputationType = SummaryComputationType.DEFAULT;
		public boolean debugManyGraphsDotOutput = false;
		public FieldPropagation fieldPropagation;
		public SideEffectDetectorConfig sideEffects = null;
		public PruningPolicy pruningPolicy = ApplicationLoaderPolicy.INSTANCE;
		public boolean computeAllocationSites = false;
		public CGConsumer cgConsumer = null;
		public ContextSelector ctxSelector = null;
		public DynamicDispatchHandling ddisp;
		public boolean isParallel = true;
		public ControlDependenceVariant controlDependenceVariant = SDGBuilder.defaultControlDependenceVariant;

		public Config(String name) {
			this(name, "<no entry defined>", FieldPropagation.OBJ_GRAPH);
		}

		public Config(String name, String entryMethod, FieldPropagation fieldPropagation) {
			this(name, entryMethod, STD_CLASS_PATH, true, PointsToPrecision.INSTANCE_BASED, DEFAULT_EXCEPTION_ANALYSIS,
					DEFAULT_ACCESS_PATH, SDGBuilder.STD_EXCLUSION_REG_EXP, Stubs.NO_STUBS,
					/* ext-call */null, "./", fieldPropagation);
		}

		public Config(String name, String entryMethod, String classpath, boolean classpathAddEntriesFromMANIFEST,
				FieldPropagation fieldPropagation) {
			this(name, entryMethod, classpath, classpathAddEntriesFromMANIFEST, PointsToPrecision.INSTANCE_BASED,
					DEFAULT_EXCEPTION_ANALYSIS, DEFAULT_ACCESS_PATH, SDGBuilder.STD_EXCLUSION_REG_EXP, Stubs.NO_STUBS,
					/* ext-call */null, "./", fieldPropagation);
		}

		public Config(String name, String entryMethod, String classpath, boolean classpathAddEntriesFromMANIFEST,
				PointsToPrecision pts, FieldPropagation fieldPropagation) {
			this(name, entryMethod, classpath, classpathAddEntriesFromMANIFEST, pts, DEFAULT_EXCEPTION_ANALYSIS,
					DEFAULT_ACCESS_PATH, SDGBuilder.STD_EXCLUSION_REG_EXP, Stubs.NO_STUBS, /* ext-call */null, "./",
					fieldPropagation);
		}

		public Config(String name, String entryMethod, String classpath, boolean classpathAddEntriesFromMANIFEST,
				String exclusions, FieldPropagation fieldPropagation) {
			this(name, entryMethod, classpath, classpathAddEntriesFromMANIFEST, PointsToPrecision.INSTANCE_BASED,
					DEFAULT_EXCEPTION_ANALYSIS, DEFAULT_ACCESS_PATH, exclusions, Stubs.NO_STUBS,
					/* ext-call */null, "./", fieldPropagation);
		}

		public Config(String name, String entryMethod, String classpath, boolean classpathAddEntriesFromMANIFEST,
				PointsToPrecision pts, String exclusions, FieldPropagation fieldPropagation) {
			this(name, entryMethod, classpath, classpathAddEntriesFromMANIFEST, pts, DEFAULT_EXCEPTION_ANALYSIS,
					DEFAULT_ACCESS_PATH, exclusions, Stubs.NO_STUBS, /* ext-call */null, "./", fieldPropagation);
		}

		public Config(String name, String entryMethod, String classpath, boolean classpathAddEntriesFromMANIFEST,
				PointsToPrecision pts, ExceptionAnalysis exceptions, boolean accessPath, String exclusions,
				Stubs stubs, ExternalCallCheck extern, String outputDir, FieldPropagation fieldPropagation) {
			this.name = name;
			this.pts = pts;
			this.exceptions = exceptions;
			this.accessPath = accessPath;
			this.classpath = classpath;
			this.classpathAddEntriesFromMANIFEST = classpathAddEntriesFromMANIFEST;
			this.entryMethod = entryMethod;
			this.exclusions = exclusions;
			this.stubs = stubs;
			this.extern = extern;

			if (!outputDir.endsWith(File.separator)) {
				this.outputDir = outputDir + File.separator;
			} else {
				this.outputDir = outputDir;
			}

			this.fieldPropagation = fieldPropagation;
		}

		@Override
		public String toString() {
			return LogUtil.attributesToString(this);
		}
	}

}
