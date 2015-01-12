package org.sugarj.common.cleardep;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.sugarj.common.AppendingIterable;
import org.sugarj.common.FileCommands;
import org.sugarj.common.cleardep.mode.DoCompileMode;
import org.sugarj.common.cleardep.mode.Mode;
import org.sugarj.common.path.Path;
import org.sugarj.common.path.RelativePath;

/**
 * Dependency management for modules.
 * 
 * @author Sebastian Erdweg
 */
abstract public class CompilationUnit extends PersistableEntity {

	public static final long serialVersionUID = -5713504273621720673L;
	
	public static enum State {
	  NEW, INITIALIZED, IN_PROGESS, SUCCESS, FAILURE;
	}

	public CompilationUnit() { /* for deserialization only */ }
	
	private State state = State.NEW;

	// Exactly one of `compiledCompilationUnit` and `editedCompilationUnit` is
	// not null.
	protected CompilationUnit compiledCompilationUnit;
	protected CompilationUnit editedCompilationUnit;

	protected Synthesizer syn;

	protected Integer interfaceHash;
	protected Path targetDir;
	// Need to declare as HashMap, because HashMap allows null values, general
	// Map does not guarantee an keys with null value would be lost
	// But HashMap is incompatible with unmodified maps which are stored in
	// persisted units. So this is not safe.
	protected Map<RelativePath, Integer> sourceArtifacts;
	protected Map<CompilationUnit, Integer> moduleDependencies;
	protected Map<CompilationUnit, Integer> circularModuleDependencies;
	protected Map<Path, Integer> externalFileDependencies;
	protected Map<Path, Integer> generatedFiles;

	// **************************
	// Methods for initialization
	// **************************

	@SuppressWarnings("unchecked")
	final protected static <E extends CompilationUnit> E create(Class<E> cl, Stamper stamper, Path compileDep, Path compileTarget, Path editedDep,
			Path editedTarget, Set<RelativePath> sourceFiles, Map<RelativePath, Integer> editedSourceFiles, Mode mode, Synthesizer syn) throws IOException {
		E compileE = PersistableEntity.tryReadElseCreate(cl, stamper, compileDep);
		compileE.targetDir = compileTarget;

		E editedE;
		if (compileE.editedCompilationUnit != null) {
			editedE = (E) compileE.editedCompilationUnit;
		  editedE.targetDir = editedTarget;
		  if (editedE.compiledCompilationUnit == null)
		    editedE.compiledCompilationUnit = compileE;		  
		}
		else {
			editedE = PersistableEntity.create(cl, stamper, editedDep);
			editedE.targetDir = editedTarget;
			editedE.compiledCompilationUnit = compileE;
			compileE.editedCompilationUnit = editedE;
		}

		E e = DoCompileMode.isDoCompile(mode) ? compileE : editedE;
		e.init();
		e.syn = syn;
		if (syn != null)
			syn.markSynthesized(e);
		e.addSourceArtifacts(sourceFiles, editedSourceFiles);
		
		return e;
	}

	/**
	 * Reads a CompilationUnit from memory or disk. The returned Compilation unit may or may not be consistent.
	 */
	@SuppressWarnings("unchecked")
	final protected static <E extends CompilationUnit> E read(Class<E> cl, Stamper stamper, Path compileDep, Path editedDep,
			Map<RelativePath, Integer> editedSourceFiles, Mode mode) throws IOException {
		E compileE = PersistableEntity.read(cl, stamper, compileDep);

		E editedE;
		if (compileE != null && compileE.editedCompilationUnit != null)
			editedE = (E) compileE.editedCompilationUnit;
		else
			editedE = PersistableEntity.read(cl, stamper, editedDep);

		if (DoCompileMode.isDoCompile(mode))
		  return compileE; 
		else
		  return editedE;
	}
	
	/**
	 * Reads a CompilationUnit from memory or disk. The returned Compilation unit is guaranteed to be consistent.
	 * 
	 * @return null if no consistent compilation unit is available.
	 */
	@SuppressWarnings("unchecked")
  final protected static <E extends CompilationUnit> E readConsistent(Class<E> cl, Stamper stamper, Path compileDep, Path editedDep,
      Map<RelativePath, Integer> editedSourceFiles, Mode mode) throws IOException {
    E compileE = PersistableEntity.read(cl, stamper, compileDep);
    if (compileE != null && compileE.isConsistent(editedSourceFiles, mode))
      // valid compile is good for compilation and parsing
      return compileE;

    E editedE;
    if (compileE != null && compileE.editedCompilationUnit != null)
      editedE = (E) compileE.editedCompilationUnit;
    else
      editedE = PersistableEntity.read(cl, stamper, editedDep);

    // valid edit is good for compilation after lifting
    if (DoCompileMode.isDoCompile(mode) && editedE != null && editedE.isConsistent(editedSourceFiles, mode)) {
      editedE.liftEditedToCompiled();
      return (E) editedE.compiledCompilationUnit;
    }

    // valid edit is good for parsing
    if (!DoCompileMode.isDoCompile(mode) && editedE != null && editedE.isConsistent(editedSourceFiles, mode))
      return editedE;

    return null;
  }

	protected void copyContentTo(CompilationUnit compiled) {
		compiled.sourceArtifacts.putAll(sourceArtifacts);

		for (Entry<CompilationUnit, Integer> entry : moduleDependencies.entrySet()) {
			CompilationUnit dep = entry.getKey();
			if (dep.compiledCompilationUnit == null)
				compiled.moduleDependencies.put(dep, entry.getValue());
			else
				compiled.addModuleDependency(dep.compiledCompilationUnit);
		}

		for (Entry<CompilationUnit, Integer> entry : circularModuleDependencies.entrySet()) {
			CompilationUnit dep = entry.getKey();
			if (dep.compiledCompilationUnit == null)
				compiled.circularModuleDependencies.put(dep, entry.getValue());
			else
			  compiled.circularModuleDependencies.put(dep.compiledCompilationUnit, dep.compiledCompilationUnit.getInterfaceHash());
		}

		for (Path p : externalFileDependencies.keySet())
			compiled.addExternalFileDependency(FileCommands.tryCopyFile(targetDir, compiled.targetDir, p));

		for (Path p : generatedFiles.keySet())
			compiled.addGeneratedFile(FileCommands.tryCopyFile(targetDir, compiled.targetDir, p));
	}

	protected void liftEditedToCompiled() throws IOException {
		ModuleVisitor<Void> liftVisitor = new ModuleVisitor<Void>() {
			@Override
			public Void visit(CompilationUnit mod, Mode mode) {
				if (mod.compiledCompilationUnit == null)
					return null;
				// throw new IllegalStateException("compiledCompilationUnit of "
				// + mod +
				// " must not be null");

				CompilationUnit edited = mod;
				CompilationUnit compiled = mod.compiledCompilationUnit;
				compiled.init();
				edited.copyContentTo(compiled);

				try {
					compiled.write();
				} catch (IOException e) {
					throw new RuntimeException(e);
				}

				return null;
			}

			@Override
			public Void combine(Void t1, Void t2) {
				return null;
			}

			@Override
			public Void init() {
				return null;
			}

			@Override
			public boolean cancel(Void t) {
				return false;
			}
		};

		if (editedCompilationUnit != null)
			editedCompilationUnit.visit(liftVisitor);
		else
			this.visit(liftVisitor);
	}

	@Override
	protected void init() {
		sourceArtifacts = new HashMap<>();
		moduleDependencies = new HashMap<>();
		circularModuleDependencies = new HashMap<>();
		externalFileDependencies = new HashMap<>();
		generatedFiles = new HashMap<>();
		this.interfaceHash = null;
		state = State.INITIALIZED;
	}

	// *******************************
	// Methods for adding dependencies
	// *******************************

	protected void addSourceArtifact(RelativePath file) {
		addSourceArtifact(file, stamper.stampOf(file));
	}

	protected void addSourceArtifact(RelativePath file, int stampOfFile) {
		sourceArtifacts.put(file, stampOfFile);
	}
	
  protected void addSourceArtifacts(Set<RelativePath> sourceFiles, Map<RelativePath, Integer> editedSourceFiles) {
    boolean hasEdits = editedSourceFiles != null;
    for (RelativePath sourceFile : sourceFiles) {
      Integer editedStamp = hasEdits ? editedSourceFiles.get(sourceFile) : null;
      if (editedStamp != null)
        addSourceArtifact(sourceFile, editedStamp);
      else
        addSourceArtifact(sourceFile);
    }
  }

	public void addExternalFileDependency(Path file) {
		addExternalFileDependency(file, stamper.stampOf(file));
	}

	public void addExternalFileDependency(Path file, int stampOfFile) {
		externalFileDependencies.put(file, stampOfFile);
	}

	public void addExternalFileDependencyLate(Path file) {
		addExternalFileDependencyLate(file, stamper.stampOf(file));
	}

	public void addExternalFileDependencyLate(Path file, int stampOfFile) {
		try {
			externalFileDependencies.put(file, stampOfFile);
		} catch (UnsupportedOperationException e) {
			externalFileDependencies = new HashMap<>(externalFileDependencies);
			externalFileDependencies.put(file, stampOfFile);
		}
	}

	public void addGeneratedFile(Path file) {
		addGeneratedFile(file, stamper.stampOf(file));
	}

	public void addGeneratedFile(Path file, int stampOfFile) {
		generatedFiles.put(file, stampOfFile);
	}

	/**
	 * @deprecated use {{@link #addModuleDependency(CompilationUnit)} instead
	 */
	@Deprecated
	public void addCircularModuleDependency(CompilationUnit mod) {
		if (!mod.dependsOnTransitivelyNoncircularly(this)) {
			throw new AssertionError("Circular depedency from " + this + " to " + mod + " does not close a circle");
		}
		circularModuleDependencies.put(mod, mod.getInterfaceHash());
	}

	public void addModuleDependency(CompilationUnit mod) {
		if (mod == this) {
			return;
		}
		if (mod.dependsOnTransitivelyNoncircularly(this)) {
			this.circularModuleDependencies.put(mod, mod.getInterfaceHash());
		} else {
			this.moduleDependencies.put(mod, mod.getInterfaceHash());
		}
	}

	/**
	 * Removes a module dependency. Note: THIS METHOD IS NOT SAFE AND LEADS TO
	 * INCONSISTENT STATES. Therefore it is protected. The Problem is that
	 * removing a module dependency may change the spanning tree at another non
	 * local point. It is necessary to repair the dependency tree after removing
	 * a dependency This is not done after removing a module dependency because
	 * it requires to rebuild all dependencies which can be done after multiple
	 * removals once.
	 * 
	 * @param mod
	 * @see GraphUtils#repairGraph(Set)
	 */
	protected void removeModuleDependency(CompilationUnit mod) {
		// Just remove from both maps because mod is exactly in one
		// But queriing is not cheaper
		this.moduleDependencies.remove(mod);
		this.circularModuleDependencies.remove(mod);
	}

	public void updateModuleDependencyInterface(CompilationUnit mod) {
		if (mod == null) {
			throw new NullPointerException("Cannot handle null unit");
		}
		if (this.moduleDependencies.containsKey(mod)) {
			this.moduleDependencies.put(mod, mod.getInterfaceHash());
		} else if (this.circularModuleDependencies.containsKey(mod)) {
			this.circularModuleDependencies.put(mod, mod.getInterfaceHash());
		} else {
			throw new IllegalArgumentException("Given CompilationUnit " + mod + " is not a dependency of this module");
		}
	}

	public void moveCircularModulDepToNonCircular(CompilationUnit mod) {
		if (mod == null) {
			throw new NullPointerException("Cannot handle null unit");
		}
		if (!this.circularModuleDependencies.containsKey(mod)) {
			throw new IllegalArgumentException("Given CompilationUnit is not a circular Dependency");
		}
		Integer value = this.circularModuleDependencies.get(mod);
		this.circularModuleDependencies.remove(mod);
		this.moduleDependencies.put(mod, value);
	}

	public void moveModuleDepToCircular(CompilationUnit mod) {
		if (mod == null) {
			throw new NullPointerException("Cannot handle null unit");
		}
		if (!this.moduleDependencies.containsKey(mod)) {
			throw new IllegalArgumentException("Given CompilationUnit is not a non circular dependency");
		}
		Integer value = this.moduleDependencies.get(mod);
		this.moduleDependencies.remove(mod);
		this.circularModuleDependencies.put(mod, value);
	}

	// *********************************
	// Methods for querying dependencies
	// *********************************

	public boolean isParsedCompilationUnit() {
		return compiledCompilationUnit != null;
	}

	public boolean dependsOnNoncircularly(CompilationUnit other) {
		return getModuleDependencies().contains(other);
	}

	public boolean dependsOnTransitivelyNoncircularly(CompilationUnit other) {
		if (dependsOnNoncircularly(other))
			return true;
		for (CompilationUnit mod : getModuleDependencies())
			if (mod.dependsOnTransitivelyNoncircularly(other))
				return true;
		return false;
	}

	public boolean dependsOn(CompilationUnit other) {
		return getModuleDependencies().contains(other) || getCircularModuleDependencies().contains(other);
	}

	public boolean dependsOnTransitively(CompilationUnit other) {
		if (dependsOn(other))
			return true;
		for (CompilationUnit mod : getModuleDependencies())
			if (mod.dependsOnTransitively(other))
				return true;
		return false;
	}

	public Set<RelativePath> getSourceArtifacts() {
		return sourceArtifacts.keySet();
	}

	public Set<CompilationUnit> getModuleDependencies() {
		return moduleDependencies.keySet();
	}

	public Set<CompilationUnit> getCircularModuleDependencies() {
		return circularModuleDependencies.keySet();
	}

	public Iterable<CompilationUnit> getCircularAndNonCircularModuleDependencies() {
		return new AppendingIterable<>(Arrays.asList(this.getModuleDependencies(), this.getCircularModuleDependencies()));
	}

	public Set<Path> getExternalFileDependencies() {
		return externalFileDependencies.keySet();
	}

	public Set<Path> getGeneratedFiles() {
		return generatedFiles.keySet();
	}

	public Set<Path> getCircularFileDependencies() throws IOException {
		Set<Path> dependencies = new HashSet<Path>();
		Set<CompilationUnit> visited = new HashSet<>();
		LinkedList<CompilationUnit> queue = new LinkedList<>();
		queue.add(this);

		while (!queue.isEmpty()) {
			CompilationUnit res = queue.pop();
			visited.add(res);

			for (Path p : res.generatedFiles.keySet())
				if (!dependencies.contains(p) && FileCommands.exists(p))
					dependencies.add(p);
			for (Path p : res.externalFileDependencies.keySet())
				if (!dependencies.contains(p) && FileCommands.exists(p))
					dependencies.add(p);

			for (CompilationUnit nextDep : res.getModuleDependencies())
				if (!visited.contains(nextDep) && !queue.contains(nextDep))
					queue.addFirst(nextDep);
			for (CompilationUnit nextDep : res.getCircularModuleDependencies())
				if (!visited.contains(nextDep) && !queue.contains(nextDep))
					queue.addFirst(nextDep);
		}

		return dependencies;
	}

	public Synthesizer getSynthesizer() {
		return syn;
	}

	// ********************************************
	// Methods for checking compilation consistency
	// ********************************************

	protected abstract boolean isConsistentExtend(Mode mode);
	
	public State getState() {
	  return state;
	}
	
  public void setState(State state) {
	  this.state = state;
	}
	
	public boolean isFinished() {
	  return state == State.FAILURE || state == State.SUCCESS;
	}
	
	public boolean hasFailed() {
	  return state == State.FAILURE;
	}

	public Integer getInterfaceHash() {
		return this.interfaceHash;
	}

	public void setInterfaceHash(Integer hash) {
		this.interfaceHash = hash;
	}

	protected boolean isConsistentWithSourceArtifacts(Map<RelativePath, Integer> editedSourceFiles, Mode mode) {
		if (sourceArtifacts.isEmpty())
			return false;

		boolean hasEdits = editedSourceFiles != null;
		for (Entry<RelativePath, Integer> e : sourceArtifacts.entrySet()) {
			Integer stamp = hasEdits ? editedSourceFiles.get(e.getKey()) : null;
			if (stamp != null && !stamp.equals(e.getValue())) {
				return false;
			} else if (stamp == null && e.getValue() != stamper.stampOf(e.getKey())) {
				return false;
			}
		}

		return true;
	}

	public boolean isConsistentShallow(Map<RelativePath, Integer> editedSourceFiles, Mode mode) {
		if (hasPersistentVersionChanged())
			return false;

		if (!isConsistentWithSourceArtifacts(editedSourceFiles, mode))
			return false;

		for (Entry<Path, Integer> e : generatedFiles.entrySet()) {
			if (stamper.stampOf(e.getKey()) != e.getValue()) {
				return false;
			}
		}

		for (Entry<? extends Path, Integer> e : externalFileDependencies.entrySet()) {
			if (stamper.stampOf(e.getKey()) != e.getValue()) {
				return false;
			}
		}

		if (!isConsistentExtend(mode))
			return false;

		return true;
	}

	public boolean isConsistentToDependencyInterfaces() {
		if (!this.isConsistentToInterfaceMap(this.moduleDependencies)) {
			return false;
		}
		return this.isConsistentToInterfaceMap(this.circularModuleDependencies);
	}

	private boolean isConsistentToInterfaceMap(Map<CompilationUnit, Integer> unitMap) {
		for (Entry<CompilationUnit, Integer> deps : unitMap.entrySet()) {
			// Get interface (use Integer because may be null)
			Integer interfaceHash = deps.getKey().getInterfaceHash();
			// A null interface is always inconsistent
			if (deps.getValue() == null) {
				return false;
			}
			// Compare current interface value to stored one
			if (!deps.getValue().equals(interfaceHash)) {
				return false;
			}
		}
		return true;
	}

	public boolean isConsistent(final Map<RelativePath, Integer> editedSourceFiles, Mode mode) {
		ModuleVisitor<Boolean> isConsistentVisitor = new ModuleVisitor<Boolean>() {
			@Override
			public Boolean visit(CompilationUnit mod, Mode mode) {
				return mod.isConsistentShallow(editedSourceFiles, mode);
			}

			@Override
			public Boolean combine(Boolean t1, Boolean t2) {
				return t1 && t2;
			}

			@Override
			public Boolean init() {
				return true;
			}

			@Override
			public boolean cancel(Boolean t) {
				return !t;
			}
		};
		return visit(isConsistentVisitor, mode);
	}

	// *************************************
	// Methods for visiting the module graph
	// *************************************

	public static interface ModuleVisitor<T> {
		public T visit(CompilationUnit mod, Mode mode);

		public T combine(T t1, T t2);

		public T init();

		public boolean cancel(T t);
	}

	/**
	 * Visits the module graph for this module. If a module m1 depends
	 * transitively on m2 and m2 does not depends transitively on m1, then m1 is
	 * visited before m2. If there is a cyclic dependency between module m1 and
	 * m2 and m1 depends on m2 transitively on the spanning directed acyclic
	 * graph, then m1 is visited before m2.
	 */
	public <T> T visit(ModuleVisitor<T> visitor) {
		return visit(visitor, null);
	}

	public <T> T visit(ModuleVisitor<T> visitor, Mode thisMode) {
		return visit(visitor, thisMode, false);
	}

	public <T> T visit(ModuleVisitor<T> visitor, Mode thisMode, boolean reverseOrder) {
		List<CompilationUnit> topologicalOrder = GraphUtils.sortTopologicalFrom(this);
		if (reverseOrder) {
			Collections.reverse(topologicalOrder);
		}

		// System.out.println("Calculated list: " + sortedUnits);

		// Now iterate over the calculated list
		T result = visitor.init();
		for (CompilationUnit mod : topologicalOrder) {
			Mode mode = thisMode;
			// Mode for required modules iff mod it not this and thismode not
			// null
			if (this != mod && mode != null) {
				mode = mode.getModeForRequiredModules();
			}
			T newResult = visitor.visit(mod, mode);
			result = visitor.combine(result, newResult);
			if (visitor.cancel(result))
				break;
		}

		return result;
	}

	// *************************
	// Methods for serialization
	// *************************

	@Override
	@SuppressWarnings("unchecked")
	protected void readEntity(ObjectInputStream in) throws IOException, ClassNotFoundException {
	  state = (State) in.readObject();
		targetDir = (Path) in.readObject();
		sourceArtifacts = (Map<RelativePath, Integer>) in.readObject();
		generatedFiles = (Map<Path, Integer>) in.readObject();
		externalFileDependencies = (Map<Path, Integer>) in.readObject();
		this.interfaceHash = (Integer) in.readObject();

		int moduleDepencyCount = in.readInt();
		moduleDependencies = new HashMap<>(moduleDepencyCount);
		for (int i = 0; i < moduleDepencyCount; i++) {
			String clName = (String) in.readObject();
			Class<? extends CompilationUnit> cl = (Class<? extends CompilationUnit>) getClass().getClassLoader().loadClass(clName);
			Path path = (Path) in.readObject();
			CompilationUnit mod = PersistableEntity.read(cl, stamper, path);
			Integer interfaceHash = (Integer) in.readObject();
			if (mod == null)
				throw new IOException("Required module cannot be read: " + path);
			moduleDependencies.put(mod, interfaceHash);
		}

		int circularModuleDependencyCount = in.readInt();
		circularModuleDependencies = new HashMap<>(circularModuleDependencyCount);
		for (int i = 0; i < circularModuleDependencyCount; i++) {
			String clName = (String) in.readObject();
			Class<? extends CompilationUnit> cl = (Class<? extends CompilationUnit>) getClass().getClassLoader().loadClass(clName);
			Path path = (Path) in.readObject();
			CompilationUnit mod = PersistableEntity.read(cl, stamper, path);
			Integer interfaceHash = (Integer) in.readObject();
			if (mod == null)
				throw new IOException("Required module cannot be read: " + path);
			circularModuleDependencies.put(mod, interfaceHash);
		}

		boolean hasSyn = in.readBoolean();
		if (hasSyn) {
			Set<CompilationUnit> modules = new HashSet<CompilationUnit>();
			int modulesCount = in.readInt();
			for (int i = 0; i < modulesCount; i++) {
				String clName = (String) in.readObject();
				Class<? extends CompilationUnit> cl = (Class<? extends CompilationUnit>) getClass().getClassLoader().loadClass(clName);
				Path path = (Path) in.readObject();
				CompilationUnit mod = PersistableEntity.read(cl, stamper, path);
				if (mod == null)
					throw new IOException("Required module cannot be read: " + path);
				modules.add(mod);
			}
			Map<Path, Integer> files = (Map<Path, Integer>) in.readObject();
			syn = new Synthesizer(modules, files);
		}
	}

	@Override
	protected void writeEntity(ObjectOutputStream out) throws IOException {
	  out.writeObject(state);
		out.writeObject(targetDir);
		out.writeObject(sourceArtifacts = Collections.unmodifiableMap(sourceArtifacts));
		out.writeObject(generatedFiles = Collections.unmodifiableMap(generatedFiles));
		out.writeObject(externalFileDependencies = Collections.unmodifiableMap(externalFileDependencies));
		out.writeObject(this.interfaceHash);

		out.writeInt(moduleDependencies.size());
		for (Entry<CompilationUnit, Integer> entry : moduleDependencies.entrySet()) {
			CompilationUnit mod = entry.getKey();
			assert mod.isPersisted() : "Required compilation units must be persisted.";
			out.writeObject(mod.getClass().getCanonicalName());
			out.writeObject(mod.persistentPath);
			out.writeObject(entry.getValue());
		}

		out.writeInt(circularModuleDependencies.size());
		for (Entry<CompilationUnit, Integer> entry : circularModuleDependencies.entrySet()) {
			CompilationUnit mod = entry.getKey();
			out.writeObject(mod.getClass().getCanonicalName());
			out.writeObject(mod.persistentPath);
			out.writeObject(entry.getValue());
		}

		out.writeBoolean(syn != null);
		if (syn != null) {
			out.writeInt(syn.generatorModules.size());
			for (CompilationUnit mod : syn.generatorModules) {
				out.writeObject(mod.getClass().getCanonicalName());
				out.writeObject(mod.persistentPath);
			}

			out.writeObject(syn.files);
		}
	}
}
