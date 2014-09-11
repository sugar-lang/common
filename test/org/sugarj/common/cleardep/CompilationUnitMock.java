package org.sugarj.common.cleardep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sugarj.common.FileCommands;
import org.sugarj.common.cleardep.CompilationUnit.ModuleVisitor;
import org.sugarj.common.cleardep.mode.ForEditorMode;
import org.sugarj.common.cleardep.mode.Mode;
import org.sugarj.common.path.AbsolutePath;
import org.sugarj.common.path.Path;
import org.sugarj.common.path.RelativePath;

/**
 * 
 * @author Simon Ramstedt
 *
 */
public class CompilationUnitMock {

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  TestFile sourceFolder;
  TestFile compileFolder;
  TestFile editedFolder;

  Stamper testStamper = ContentHashStamper.instance;
  Mode editorMode = new ForEditorMode(null, true);
  TestCompilationUnit a, b, c, d, e;
  TestFile f1, f2, f3, f4, f5;
  Map<RelativePath, Integer> sourceArtifacts;

  @Before
  public void setUp() throws Exception {

    sourceFolder = new TestFile(tempFolder.newFolder("source"));
    compileFolder = new TestFile(tempFolder.newFolder("compile"));
    editedFolder = new TestFile(tempFolder.newFolder("edited"));

    // test modules
    a = generateRandomModule();
    b = generateRandomModule();
    c = generateRandomModule();
    d = generateRandomModule();
    e = generateRandomModule();

    // test files
    f1 = generateRandomFileIn(editedFolder);
    f2 = generateRandomFileIn(editedFolder);
    f3 = generateRandomFileIn(editedFolder);
    f4 = generateRandomFileIn(editedFolder);
    f5 = generateRandomFileIn(editedFolder);

  }

  @After
  public void tearDown() throws Exception {

  }

 
  // Tests

  @Test
  public void test_getCircularFileDependencies() throws IOException {

    Assert.assertArrayEquals(new Path[0], a.getCircularFileDependencies().toArray());
    a.addExternalFileDependency(f1);
    a.addModuleDependency(b);
    b.addExternalFileDependency(f1);
    Path[] cfd = { f1 };
    Assert.assertArrayEquals(cfd, a.getCircularFileDependencies().toArray());
  }

  @Test
  public void test_dependsOn_1() throws Exception {

    a.addModuleDependency(b); // a -> b

    assertTrue(a.dependsOnNoncircularly(b));
    assertTrue(a.dependsOn(b));
    assertTrue(a.dependsOnTransitively(b));

    b.addModuleDependency(c); // a -> b -> c

    assertTrue(a.dependsOnTransitivelyNoncircularly(c));
    assertTrue(a.dependsOnTransitively(c));

  }

  @Test
  public void test_dependsOn_2() throws Exception {

    a.addCircularModuleDependency(b); // a <-> b
    b.addModuleDependency(a);

    assertTrue(a.dependsOn(b));
    assertFalse(a.dependsOnNoncircularly(b));

    b.addModuleDependency(c); // a <-> b -> c

    assertTrue(a.dependsOnTransitively(c));
    assertFalse(a.dependsOnTransitivelyNoncircularly(c));

  }

  @Test
  public void test_dependsOn_3() throws Exception {

    a.addModuleDependency(b); // a -> b
    b.addCircularModuleDependency(c); // a -> b <-> c
    c.addModuleDependency(b);

    assertTrue(a.dependsOnTransitively(c));
    assertFalse(a.dependsOnTransitivelyNoncircularly(c));

  }

  @Test
  public void test_addModuleDependency() throws Exception {

    a.addModuleDependency(b);
    b.addModuleDependency(c);
    c.addModuleDependency(a);
    assertTrue(a.circularModuleDependencies.isEmpty());
    assertTrue(b.circularModuleDependencies.isEmpty());
    assertTrue(a.moduleDependencies.contains(b));
    assertTrue(b.moduleDependencies.contains(c));
    assertTrue(c.circularModuleDependencies.contains(a));
  }

  @Test
  public void test_isConsistentShallow_1() throws Exception {

    assertTrue(a.isConsistentShallow(null, editorMode));

    TestFile f1 = generateRandomFileIn(sourceFolder);
    // source artifacts
    a.addSourceArtifact(f1.relativeTo(sourceFolder));
    assertTrue(a.isConsistentShallow(null, editorMode));
    FileCommands.delete(f1);
    assertFalse(a.isConsistentShallow(null, editorMode));

  }

  @Test
  public void test_isConsistentShallow_2() throws Exception {

    a.addExternalFileDependency(f1);
    assertTrue(a.isConsistentShallow(null, editorMode));
    FileCommands.delete(f1);
    assertFalse(a.isConsistentShallow(null, editorMode));

    b.addExternalFileDependency(f2);
    assertTrue(b.isConsistentShallow(null, editorMode));
    changeContentOf(f2);
    assertFalse(b.isConsistentShallow(null, editorMode));

  }

  @Test
  public void test_isConsistentShallow_3() throws Exception {

    a.addGeneratedFile(f1);
    assertTrue(a.isConsistentShallow(null, editorMode));
    FileCommands.delete(f1);
    assertFalse(a.isConsistentShallow(null, editorMode));

    b.addGeneratedFile(f2);
    assertTrue(b.isConsistentShallow(null, editorMode));
    changeContentOf(f2);
    assertFalse(b.isConsistentShallow(null, editorMode));
  }

  @Test
  public void test_isConsistent_1() throws Exception {

    assertTrue(a.isConsistent(null, editorMode));

    // shallow dependency
    a.addExternalFileDependency(f1);
    assertTrue(a.isConsistent(null, editorMode));
    FileCommands.delete(f1);
    assertFalse(a.isConsistent(null, editorMode));

  }

  @Test
  public void test_isConsistent_2() throws Exception {

    // a -> b
    
    a.addModuleDependency(b);
    assertTrue(a.isConsistent(null, editorMode));

    // a -> b (b inconsistent)
    b.addExternalFileDependency(f1);
    assertTrue(a.isConsistent(null, editorMode));
    FileCommands.delete(f1);
    assertFalse(a.isConsistent(null, editorMode));
  }

  @Test
  public void test_isConsistent_3() throws Exception {
    
    // a <-> b
    a.addCircularModuleDependency(b);
    b.addModuleDependency(a);
    assertTrue(a.isConsistent(null, editorMode));

    // a <-> b (b inconsistent)
    b.addExternalFileDependency(f1);
    assertTrue(a.isConsistent(null, editorMode));
    FileCommands.delete(f1);
    assertFalse(a.isConsistent(null, editorMode));

  }

  @Test
  public void test_isConsistent_4() throws Exception {

    // a -> b -> c
    a.addModuleDependency(b);
    b.addModuleDependency(c);
    assertTrue(a.isConsistent(null, editorMode));

    // a -> b -> c (c inconsistent)
    c.addExternalFileDependency(f1);
    assertTrue(a.isConsistent(null, editorMode));
    FileCommands.delete(f1);
    assertFalse(a.isConsistent(null, editorMode));

  }

  @Test
  public void test_isConsistent_5() throws Exception {

    // a -> x (synth) -> b,c
    
    Set<CompilationUnit> synModules = new HashSet<>();
    synModules.add(b);
    synModules.add(c);

    Set<Path> externalDependencies = new HashSet<Path>();
    externalDependencies.add(f1);

    TestCompilationUnit x = generateRandomModule(new Synthesizer(testStamper, synModules, externalDependencies));


    a.addModuleDependency(x);


    assertTrue(a.isConsistent(null, editorMode));
    FileCommands.delete(f1);
    assertFalse(a.isConsistent(null, editorMode));
  }

  @Test
  public void test_isConsistent_6() throws Exception {

    // a -> x (synth) -> b,c (c inconsistent)

    Set<CompilationUnit> synModules = new HashSet<>();
    synModules.add(b);
    synModules.add(c);

    Set<Path> externalDependencies = new HashSet<Path>();
    externalDependencies.add(f1);

    TestCompilationUnit x = generateRandomModule(new Synthesizer(testStamper, synModules, externalDependencies));


    a.addModuleDependency(x);

    // make c inconsistent
    c.addGeneratedFile(f2);
    assertTrue(a.isConsistent(null, editorMode));
    
    FileCommands.delete(f2);
    assertFalse(a.isConsistent(null, editorMode));
  }

  @Test
  public void test_liftEditedToCompiled() throws IOException {

    TestFile e1CompiledDep = randomPathIn(sourceFolder);
    TestFile e2CompiledDep = randomPathIn(sourceFolder);
    TestFile e3CompiledDep = randomPathIn(sourceFolder);

    TestFile e1EditedDep = randomPathIn(sourceFolder);
    TestFile e2EditedDep = randomPathIn(sourceFolder);
    TestFile e3EditedDep = randomPathIn(sourceFolder);

    Set<RelativePath> e1SourceFiles = new HashSet<RelativePath>();

    Set<RelativePath> e2SourceFiles = new HashSet<RelativePath>();

    Set<RelativePath> e3SourceFiles = new HashSet<RelativePath>();

    TestCompilationUnit e1 = TestCompilationUnit.create(TestCompilationUnit.class, testStamper, e1CompiledDep, compileFolder, e1EditedDep, editedFolder, e1SourceFiles, null, new ForEditorMode(null, true), null);

    e1.addExternalFileDependency(f1);
    e1.addGeneratedFile(f2);

    TestCompilationUnit e2 = TestCompilationUnit.create(TestCompilationUnit.class, testStamper, e2CompiledDep, compileFolder, e2EditedDep, editedFolder, e2SourceFiles, null, new ForEditorMode(null, true), null);

    e2.addExternalFileDependency(f1);
    e2.addExternalFileDependency(f3);
    e2.addGeneratedFile(f4);

    TestCompilationUnit e3 = TestCompilationUnit.create(TestCompilationUnit.class, testStamper, e3CompiledDep, compileFolder, e3EditedDep, editedFolder, e3SourceFiles, null, new ForEditorMode(null, true), null);

    e3.addExternalFileDependency(f1);
    e3.addGeneratedFile(f2);
    e3.addGeneratedFile(f5);

    e1.addModuleDependency(e2);
    e2.addModuleDependency(e3);

    e1.liftEditedToCompiled();

    TestFile cf1 = new TestFile(compileFolder, f1.name());
    TestFile cf2 = new TestFile(compileFolder, f2.name());
    TestFile cf3 = new TestFile(compileFolder, f3.name());
    TestFile cf4 = new TestFile(compileFolder, f4.name());
    TestFile cf5 = new TestFile(compileFolder, f5.name());

    assertEquals(cf1, f1);
    assertEquals(cf2, f2);
    assertEquals(cf3, f3);
    assertEquals(cf4, f4);
    assertEquals(cf5, f5);

    TestCompilationUnit c1 = (TestCompilationUnit) e1.compiledCompilationUnit;
    TestCompilationUnit c2 = (TestCompilationUnit) e2.compiledCompilationUnit;
    TestCompilationUnit c3 = (TestCompilationUnit) e3.compiledCompilationUnit;

    assertTrue(c1.getModuleDependencies().iterator().next() == c2);
    assertTrue(c2.getModuleDependencies().iterator().next() == c3);

  }

  @Test
  public void test_liftEditedToCompiled_WithSynthesizer() throws IOException {



   a.addExternalFileDependency(f1);
    a.addGeneratedFile(f2);

    b.addExternalFileDependency(f1);
    b.addExternalFileDependency(f3);
    b.addGeneratedFile(f4);

    // Synthesizer
    Set<CompilationUnit> modules = new HashSet<CompilationUnit>();
    Map<Path, Integer> files = new HashMap<Path, Integer>();
    modules.add(a);
    modules.add(b);
    files.put(f5, testStamper.stampOf(f5));
    Synthesizer syn = new Synthesizer(modules, files);

    TestFile compiledDep = randomPathIn(sourceFolder);
    TestFile editedDep = randomPathIn(sourceFolder);
    Set<RelativePath> sourceFiles = new HashSet<RelativePath>();
    TestCompilationUnit compilationUnitWithSynthesizer = TestCompilationUnit.create(TestCompilationUnit.class, testStamper, compiledDep, compileFolder, editedDep, editedFolder, sourceFiles, null, new ForEditorMode(null, true), syn);

    compilationUnitWithSynthesizer.liftEditedToCompiled();

    TestFile cf1 = new TestFile(compileFolder, f1.name());
    TestFile cf2 = new TestFile(compileFolder, f2.name());
    TestFile cf3 = new TestFile(compileFolder, f3.name());
    TestFile cf4 = new TestFile(compileFolder, f4.name());
    TestFile cf5 = new TestFile(compileFolder, f5.name());

    assertEquals(cf1, f1);
    assertEquals(cf2, f2);
    assertEquals(cf3, f3);
    assertEquals(cf4, f4);
    assertEquals(cf5, f5);

    TestCompilationUnit c1 = (TestCompilationUnit) a.compiledCompilationUnit;
    TestCompilationUnit c2 = (TestCompilationUnit) b.compiledCompilationUnit;
    TestCompilationUnit c3 = (TestCompilationUnit) compilationUnitWithSynthesizer.compiledCompilationUnit;

    assertTrue(c3.getModuleDependencies().contains(c1));
    assertTrue(c3.getModuleDependencies().contains(c2));

    Assert.assertNotNull(c3.getSynthesizer());

    assertTrue(c3.getSynthesizer().modules.contains(c1));
    assertTrue(c3.getSynthesizer().modules.contains(c2));
  }

  @Test
  public void test_visit() {

    final List<CompilationUnit> visited = new LinkedList<CompilationUnit>();

    ModuleVisitor<Void> v = new ModuleVisitor<Void>() {

      @Override
      public Void visit(CompilationUnit mod, Mode mode) {

        visited.add(mod);

        return null;
      }

      @Override
      public Void init() {
        return null;
      }

      @Override
      public Void combine(Void t1, Void t2) {
        return null;
      }

      @Override
      public boolean cancel(Void t) {
        return false;
      }
    };

    a.addModuleDependency(b);
    b.addModuleDependency(c);
    b.addModuleDependency(d);
    d.addModuleDependency(e);
    e.addCircularModuleDependency(a);

    a.visit(v);

    Object[] expected = { a, b, c, d, e };
    Assert.assertArrayEquals(expected, visited.toArray());

  }

  @Test
  public void test_readWrite() throws IOException {

    TestFile e1CompiledDep = randomPathIn(sourceFolder);

    TestFile e1EditedDep = randomPathIn(sourceFolder);

    Set<RelativePath> e1SourceFiles = new HashSet<RelativePath>();

    // Synthesizer
    Set<CompilationUnit> modules = new HashSet<CompilationUnit>();
    Map<Path, Integer> files = new HashMap<Path, Integer>();

    modules.add(b);

    files.put(f5, testStamper.stampOf(f5));

    Synthesizer syn = new Synthesizer(modules, files);

    TestCompilationUnit e1 = TestCompilationUnit.create(TestCompilationUnit.class, testStamper, e1CompiledDep, compileFolder, e1EditedDep, editedFolder, e1SourceFiles, null, new ForEditorMode(null, true), syn);

    e1.addModuleDependency(c);
    e1.addCircularModuleDependency(d);
    e1.addExternalFileDependency(f1);
    e1.addGeneratedFile(f2);

    e1.write();

    e1 = null;
    e1 = TestCompilationUnit.read(TestCompilationUnit.class, testStamper, e1EditedDep);

    Assert.assertNotNull(e1.getSynthesizer());
    assertEquals(b.persistentPath.getAbsolutePath(), e1.getSynthesizer().modules.iterator().next().persistentPath.getAbsolutePath());

    List<String> names = new LinkedList<String>();
    for (CompilationUnit c : e1.getModuleDependencies()) {
      names.add(c.getName());
    }
    assertTrue(names.contains(b.getName()));
    assertTrue(names.contains(c.getName()));

    assertEquals(d.persistentPath.getAbsolutePath(), e1.circularModuleDependencies.iterator().next().persistentPath.getAbsolutePath());

    List<String> paths = new LinkedList<String>();
    for (Path p : e1.getExternalFileDependencies()) {
      paths.add(p.getAbsolutePath());
    }
    assertTrue(paths.contains(f5.getAbsolutePath()));
    assertTrue(paths.contains(f1.getAbsolutePath()));

    assertEquals(f2.getAbsolutePath(), e1.getGeneratedFiles().iterator().next().getAbsolutePath());
  }

  // Helper methods
  
  /**
   * 
   * @param length
   * @return random string containing only lower case letters
   */
  public static String randomString(int length) {

    char[] chars = "abcdefghijklmnopqrstuvwxyz".toCharArray();
    StringBuilder sb = new StringBuilder();
    Random random = new Random();
    for (int i = 0; i < length; i++) {
      char c = chars[random.nextInt(chars.length)];
      sb.append(c);
    }
    return sb.toString();
  }

  public TestFile randomPathIn(TestFile folder) {

    return new TestFile(folder, randomString(15));
  }

  public TestFile generateRandomFileIn(TestFile folder) throws IOException {
    TestFile file = new TestFile(folder, randomString(15), randomString(300));
    
    return file;
  }

  public static void changeContentOf(TestFile file) throws IOException {

    if (FileCommands.exists(file))
      file.write(randomString(300));
    else
      throw new IllegalStateException("File to be changed doesn't exist");
  }

  public TestCompilationUnit generateRandomModule() throws IOException {
    return generateRandomModule(null);
  }

  public TestCompilationUnit generateRandomModule(Synthesizer synthesizer) throws IOException {

    Set<RelativePath> srcFiles = new HashSet<RelativePath>();
    srcFiles.add(generateRandomFileIn(sourceFolder).relativeTo(sourceFolder));

    Map<RelativePath, Integer> editedSourceFiles = new HashMap<RelativePath, Integer>();

    return TestCompilationUnit.create(TestCompilationUnit.class, 
        testStamper, 
 randomPathIn(sourceFolder),
        compileFolder, 
 randomPathIn(sourceFolder),
        editedFolder, 
        srcFiles, 
        editedSourceFiles, 
        editorMode,
        synthesizer);
  }


  // Helper Classes

  public class TestFile extends AbsolutePath {

    public TestFile(AbsolutePath folder, String filename) {

      super(folder.getAbsolutePath() + "/" + filename);
    }
    public TestFile(AbsolutePath folder, String filename, String content) throws IOException {

      super(folder.getAbsolutePath() + "/" + filename);
      write(content);

    }

    public String name() {

      return getFile().getName();
    }

    public RelativePath relativeTo(TestFile folder) {

      return FileCommands.getRelativePath(folder, this);
    }

    public TestFile(File f) {
      super(f.getAbsolutePath());
    }

    public String read() throws IOException {
      return FileCommands.readFileAsString(this);
    }

    public void write(String content) throws IOException {

        FileCommands.writeToFile(this, content);
    }

    @Override
    public boolean equals(Object o) {

      if (o instanceof TestFile) {
        try {
          if (((TestFile) o).read().equals(this.read()))
            return true;
        } catch (IOException e) {
          e.printStackTrace();
        }
      }

      return false;
    }

  }

}
