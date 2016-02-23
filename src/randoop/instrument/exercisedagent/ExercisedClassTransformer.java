package randoop.instrument.exercisedagent;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.reflect.Modifier;
import java.security.ProtectionDomain;

import randoop.BugInRandoopException;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtField;
import javassist.CtMethod;

/**
 * A {@link java.lang.instrument.ClassTransformer} that instruments loaded
 * classes to determine if exercised.
 * Does the following instrumentation of each class:
 * <ol>
 * <li> Adds a static boolean flag to the class. Initially set to false.
 * <li> Adds a statement at the beginning of each method and constructor that
 *      sets the flag.
 * <li> Adds a static method that polls and resets the value of the flag.
 * </ol>
 * Avoids instrumenting JDK classes and skips interfaces.
 * Otherwise, all other classes are instrumented.
 */
public class ExercisedClassTransformer implements ClassFileTransformer {

  /** the class pool used to load class files */
  private ClassPool pool;

  /**
   * Create {@code ExercisedClassTransformer}.
   */
  public ExercisedClassTransformer() {
    super();
    pool = ClassPool.getDefault();
  }

  /**
   * {@inheritDoc}
   * Transforms bytecode for a class by adding "exercised" instrumentation.
   * Avoids JDK classes, interfaces and any "frozen" classes that have already
   * been loaded.
   */
  @Override
  public byte[] transform(ClassLoader loader, String className,
      Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
      byte[] classfileBuffer)
      throws IllegalClassFormatException {

    byte[] bytecode = null;

    // don't transform rt.jar classes
    // list derived from jdk1.8.0_71
    if (className.startsWith("java.")
        || className.startsWith("javax.")
        || className.startsWith("jdk.")
        || className.startsWith("apple.")
        || className.startsWith("com.apple.")
        || className.startsWith("com.oracle.")
        || className.startsWith("com.sun.")
        || className.startsWith("org.ietf.")
        || className.startsWith("org.jcp.")
        || className.startsWith("org.omg.")
        || className.startsWith("org.w3c")
        || className.startsWith("org.xml.")
        || className.startsWith("sun.")) {
      return bytecode;
    }

    CtClass cc = null;
    try {
      cc = pool.makeClassIfNew(new ByteArrayInputStream(classfileBuffer));
    } catch (Exception e) {
      throw new BugInRandoopException("Unable to instrument file: " + e);
    }

    if (cc.isFrozen() || cc.isInterface()) {
      return bytecode;
    }

    // OK to transform bytecode
    modifyBytecode(cc);
    try {
      bytecode = cc.toBytecode();
    } catch (IOException e) {
      throw new BugInRandoopException("Unable to convert instrumentation to bytecode: " + e);
    } catch (CannotCompileException e) {
      throw new BugInRandoopException("Error in instrumentation code: " + e);
    }
    cc.detach(); // done with class, remove from ClassPool

    return bytecode;
  }

  /**
   * Instruments the bytecode of the given class object to track constructor and
   * method calls for the class. Modifies each method and constructor to set an
   * inserted private field that keeps track.
   * Adds a public method {@code boolean randoop_checkAndReset()}
   *
   * @param cc  the {@code javassist.CtClass} object
   * @throws InstrumentationException
   * @throws CannotCompileException
   *           if inserted code doesn't compile
   */
  private void modifyBytecode(CtClass cc) {
    // add static field
    String flagFieldName = "randoop_classUsedFlag";
    try {
      CtField flagField = new CtField(CtClass.booleanType, flagFieldName, cc);
      flagField.setModifiers(Modifier.STATIC);
      cc.addField(flagField, "false");
    } catch (CannotCompileException e) {
      throw new Error("error adding instrumentation field: " + e);
    }
    String flagFieldAccess = cc.getName() + "#" + flagFieldName;

    // add code to entry of each method to indicate that called
    String statementToSetFlag = flagFieldAccess + " = true" + ";";

    // instrument methods *before* adding polling method
    try {
      for (CtMethod m : cc.getMethods()) {
        int mods = m.getModifiers();
        if (!Modifier.isNative(mods) && !Modifier.isAbstract(mods)) {
          m.insertBefore(statementToSetFlag);
        }
      }
    } catch (CannotCompileException e) {
      throw new Error("error instrumenting method: " + e);
    }

    // instrument constructors
    try {
      for (CtConstructor c : cc.getConstructors()) {
        c.insertBefore(statementToSetFlag);
      }
    } catch (CannotCompileException e) {
      throw new Error("error instrumenting constructor: " + e);
    }

    // add static method that polls and resets the exercised flag
    try {
      String methodName = "randoop_checkAndReset";
      CtMethod pollMethod = new CtMethod(CtClass.booleanType, methodName, new CtClass[0], cc);
      pollMethod.setBody("{"
           + "boolean state = " + flagFieldAccess + "; "
           + flagFieldAccess + " = false" + ";"
           + "return state" + ";" + "}");
      pollMethod.setModifiers(Modifier.STATIC | Modifier.PUBLIC);
      cc.addMethod(pollMethod);
    } catch (CannotCompileException e) {
      throw new Error("error adding instrumentation method: " + e);
    }

  }
}
