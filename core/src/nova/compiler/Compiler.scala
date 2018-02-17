package nova.compiler

import forge.io.files
import forge.utils.plural
import forge.Instrument
import forge.Instrumented
import nova.core._
import nova.traversal.Pass
import nova.traversal.transform.Transformer

abstract class Compiler { self =>
  protected var IR: State = new State
  final implicit def __IR: State = IR
  private val instrument = new Instrument()

  def name: String = self.getClass.getName.replace("class ", "").replace('.','_').replace("$","")
  protected val testbench: Boolean = false

  protected def checkBugs(stage: String): Unit = if (IR.hadBugs) throw CompilerBugs(stage, IR.bugs)
  protected def checkErrors(stage: String): Unit = if (IR.hadErrors) throw CompilerErrors(stage, IR.errors)
  protected def checkWarnings(): Unit = if (IR.hadWarnings) {
    warn(s"""${IR.warnings} ${plural(IR.warnings, "warning","warnings")} found""")
  }

  protected def onException(t: Throwable): Unit = {
    val trace = t.getStackTrace
    val oldLL = config.logLevel
    config.logLevel = 2
    withLog(config.logDir, config.name + "_exception.log") {
      if (t.getMessage != null) { log(t.getMessage); log("") }
      if (t.getCause != null) { log(t.getCause); log("") }
      trace.foreach{elem => log(elem.toString) }
    }
    config.logLevel = oldLL
    bug(s"An exception was encountered while compiling ${config.name}: ")
    if (t.getMessage != null) bug(s"  ${t.getMessage}")
    if (t.getCause != null) bug(s"  ${t.getCause}")
    else bug(s"  $t")
    if (config.enDbg) {
      trace.take(20).foreach{t => bug("  " + t) }
      if (trace.length > 20) bug(s" .. [see ${config.logDir}${config.name}_exception.log]")
    }
    bug(s"This is due to a compiler bug. A log file has been created at: ")
    bug(s"  ${config.logDir}${config.name}_exception.log")
  }

  def stage(args: Array[String]): Block[_]
  def settings(): Unit = { }
  def runPasses[R](b: Block[R]): Unit

  final def runPass[R](t: Pass, block: Block[R]): Block[R] = instrument(t.name){
    if (t.isInstanceOf[Transformer]) {
      globals.clearBeforeTransform()
    }
    val issuesBefore = state.issues

    if (t.needsInit) t.init()
    val result = t.run(block)
    // After each traversal, check whether there were any reported errors or unresolved issues
    val issuesAfter = state.issues
    val persistingIssues = issuesBefore intersect issuesAfter
    persistingIssues.foreach{_.onUnresolved(t.name) }
    checkBugs(t.name)
    checkErrors(t.name)

    // Mirror after transforming
    t match {case f: Transformer => globals.mirrorAfterTransform(f); case _ => }

    result
  }

  final def stageProgram(args: Array[String]): Block[_] = instrument("Staging"){
    val block = withLog(config.logDir, "0000 Staging.log"){ stage(args) }
    checkBugs("staging")
    checkErrors("staging") // Exit now if errors were found during staging
    block
  }

  final def compileProgram(args: Array[String]): Unit = instrument("compile"){
    val block = stageProgram(args)
    runPasses(block)
  }

  final def echoConfig(): Unit = {
    info(s"Name: ${config.name}")
    info(s"Rep directory: ${config.repDir}")
    info(s"Log directory: ${config.logDir}")
    info(s"Gen directory: ${config.genDir}")
    info(s"Show warnings: ${config.enWarn}")
    info(s"Show errors:   ${config.enError}")
    info(s"Show infos:    ${config.enInfo}")
    info(s"Enable logging: ${config.enDbg}")
    info(s"Enable verbose: ${config.enLog}")
  }

  // TODO: Copy globals (created prior to the main method) to the new state's graph?
  final def init(args: Array[String]): Unit = instrument("init"){
    IR = new State                 // Create a new, empty state

    IR.config.init(args)           // Initialize the Config (from files)
    IR.config.name = name          // Set the default program name
    IR.config.logDir = files.cwd + files.sep + "logs" + files.sep + name + files.sep
    IR.config.genDir = files.cwd + files.sep + "gen" + files.sep + name + files.sep
    IR.config.repDir = files.cwd + files.sep + "reports" + files.sep + name + files.sep

    settings()                     // Override config with any DSL or app-specific settings

    msg(s"Compiling ${config.name} to ${config.genDir}")
    if (config.enLog) msg(s"Logging ${config.name} to ${config.logDir}")

    files.deleteExts(config.logDir, ".log")
  }

  def execute(args: Array[String]): Unit = instrument("nova"){
    init(args)
    compileProgram(args)
  }

  /**
    * The "real" entry point for the application
    */
  def main(args: Array[String]): Unit = {
    instrument.reset()
    try {
      execute(args)
    }
    catch {
      case e @ CompilerBugs(stage,n) =>
        onException(new Exception(s"$n compiler ${plural(n,"bug")} during pass $stage"))
        if (testbench) throw TestbenchFailure(s"$n compiler ${plural(n,"bug")} during pass $stage")

      case e @ CompilerErrors(stage,n) =>
        error(s"${IR.errors} ${plural(n,"error")} found during $stage")
        if (testbench) throw TestbenchFailure(s"$n ${plural(n,"error")} found during $stage")

      case t: Throwable =>
        onException(t)
        if (testbench) throw TestbenchFailure(s"Uncaught exception ${t.getMessage}")
    }

    checkWarnings()
    val tag = {
      if (IR.hadBugs || IR.hadErrors) s"[${Console.RED}failed${Console.RESET}]"
      else s"[${Console.GREEN}success${Console.RESET}]"
    }

    instrument.dump("Nova Profiling Report", getOrCreateStream(config.logDir,"9999_Timing.log"))
    Instrumented.set.foreach{i =>
      val log = i.fullName.replace('.','_')
      val heading = s"${i.instrumentName}: ${i.toString} (${i.hashCode()})"
      val stream = getOrCreateStream(config.logDir,log + ".log")
      i.dumpInstrument(heading, stream)
      stream.println("\n")
      info(s"Profiling results for ${i.fullName} dumped to ${config.logDir}$log.log")
      i.resetInstrument()
    }

    val time = instrument.totalTime
    msg(s"$tag Total time: " + "%.4f".format(time/1000.0f) + " seconds")

    IR.streams.values.foreach{stream => stream.close() }
  }
}
