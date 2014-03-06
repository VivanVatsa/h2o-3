package water;

import java.net.*;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicLong;
import water.init.*;
import water.util.*;

/**
* Start point for creating or joining an <code>H2O</code> Cloud.
*
* @author <a href="mailto:cliffc@0xdata.com"></a>
* @version 1.0
*/
public final class H2O {

  public static final AbstractBuildVersion ABV;
  static {
    AbstractBuildVersion abv = AbstractBuildVersion.UNKNOWN_VERSION;
    try {
      Class klass = Class.forName("water.BuildVersion");
      java.lang.reflect.Constructor constructor = klass.getConstructor();
      abv = (AbstractBuildVersion) constructor.newInstance();
    } catch (Exception _) { }
    ABV = abv;
  }

  // Atomically set once during startup.  Guards against repeated startups.
  public static AtomicLong START_TIME_MILLIS = new AtomicLong(); // When did main() run

  // Used to gate default worker threadpool sizes
  public static final int NUMCPUS = Runtime.getRuntime().availableProcessors();

  // List of arguments.
  public static OptArgs ARGS = new OptArgs();
  public static class OptArgs extends Arguments.Opt {
    public boolean h = false;
    public boolean help = false;
    public boolean version = false;

    // Common config options
    public String name = System.getProperty("user.name"); // Cloud name
    public String flatfile;     // List of cluster IP addresses
    public int port=54321;      // Browser/API/HTML port
    public int h2o_port;        // port+1
    public String ip;           // Named IP4/IP6 address instead of the default
    public String network; // Network specification for acceptable interfaces to bind to.
    public String ice_root;     // ice root directory; where temp files go

    // Less common config options
    public int nthreads=Math.max(99,10*NUMCPUS); // Max number of F/J threads in the low-priority batch queue
    public boolean random_udp_drop; // test only, randomly drop udp incoming
    public boolean requests_log = true; // logging of Web requests
    public boolean check_rest_params = true; // enable checking unused/unknown REST params

  }

  public static int H2O_PORT; // Both TCP & UDP cluster ports
  public static int API_PORT; // RequestServer and the API HTTP port

  // The multicast discovery port
  static public MulticastSocket  CLOUD_MULTICAST_SOCKET;
  static public NetworkInterface CLOUD_MULTICAST_IF;
  static public InetAddress      CLOUD_MULTICAST_GROUP;
  static public int              CLOUD_MULTICAST_PORT ;

  // Myself, as a Node in the Cloud
  public static H2ONode SELF = null;
  public static InetAddress SELF_ADDRESS;

  // Persistence schemes; used as file prefixes eg "hdfs://some_hdfs_path/some_file"
  public static class Schemes {
    public static final String FILE = "file";
    public static final String HDFS = "hdfs";
    public static final String S3 = "s3";
    public static final String NFS = "nfs";
  }

  // Place to store temp/swap files
  public static URI ICE_ROOT;
  public static String DEFAULT_ICE_ROOT() {
    String username = System.getProperty("user.name");
    if (username == null) username = "";
    String u2 = username.replaceAll(" ", "_");
    if (u2.length() == 0) u2 = "unknown";
    return "/tmp/h2o-" + u2;
  }

  // Static list of acceptable Cloud members
  public static HashSet<H2ONode> STATIC_H2OS = null;

  // Reverse cloud index to a cloud; limit of 256 old clouds.
  static private final H2O[] CLOUDS = new H2O[256];

  // Enables debug features like more logging and multiple instances per JVM
  public static final String DEBUG_ARG = "h2o.debug";
  public static final boolean DEBUG = System.getProperty(DEBUG_ARG) != null;

  public static void printHelp() {
    String s =
    "Start an H2O node.\n" +
    "\n" +
    "Usage:  java [-Xmx<size>] -jar h2o.jar [options]\n" +
    "        (Note that every option has a default and is optional.)\n" +
    "\n" +
    "    -h | -help\n" +
    "          Print this help.\n" +
    "\n" +
    "    -version\n" +
    "          Print version info and exit.\n" +
    "\n" +
    "    -name <h2oCloudName>\n" +
    "          Cloud name used for discovery of other nodes.\n" +
    "          Nodes with the same cloud name will form an H2O cloud\n" +
    "          (also known as an H2O cluster).\n" +
    "\n" +
    "    -flatfile <flatFileName>\n" +
    "          Configuration file explicitly listing H2O cloud node members.\n" +
    "\n" +
    "    -ip <ipAddressOfNode>\n" +
    "          IP address of this node.\n" +
    "\n" +
    "    -port <port>\n" +
    "          Port number for this node (note: port+1 is also used).\n" +
    "          (The default port is " + ARGS.port + ".)\n" +
    "\n" +
    "    -network <IPv4network1Specification>[,<IPv4network2Specification> ...]\n" +
    "          The IP address discovery code will bind to the first interface\n" +
    "          that matches one of the networks in the comma-separated list.\n" +
    "          Use instead of -ip when a broad range of addresses is legal.\n" +
    "          (Example network specification: '10.1.2.0/24' allows 256 legal\n" +
    "          possibilities.)\n" +
    "\n" +
    "    -ice_root <fileSystemPath>\n" +
    "          The directory where H2O spills temporary data to disk.\n" +
    "          (The default is '" + ARGS.port + "'.)\n" +
    "\n" +
    "    -nthreads <#threads>\n" +
    "          Maximum number of threads in the low priority batch-work queue.\n" +
    "          (The default is 99.)\n" +
    "\n" +
    "Cloud formation behavior:\n" +
    "\n" +
    "    New H2O nodes join together to form a cloud at startup time.\n" +
    "    Once a cloud is given work to perform, it locks out new members\n" +
    "    from joining.\n" +
    "\n" +
    "Examples:\n" +
    "\n" +
    "    Start an H2O node with 4GB of memory and a default cloud name:\n" +
    "        $ java -Xmx4g -jar h2o.jar\n" +
    "\n" +
    "    Start an H2O node with 6GB of memory and a specify the cloud name:\n" +
    "        $ java -Xmx6g -jar h2o.jar -name MyCloud\n" +
    "\n" +
    "    Start an H2O cloud with three 2GB nodes and a default cloud name:\n" +
    "        $ java -Xmx2g -jar h2o.jar &\n" +
    "        $ java -Xmx2g -jar h2o.jar &\n" +
    "        $ java -Xmx2g -jar h2o.jar &\n" +
    "\n";

    System.out.print(s);
  }

  /** If logging has not been setup yet, then Log.info will only print to
   *  stdout.  This allows for early processing of the '-version' option
   *  without unpacking the jar file and other startup stuff.  */
  public static void printAndLogVersion() {
    Log.info("----- H2O started -----");
    Log.info("Build git branch: " + ABV.branchName());
    Log.info("Build git hash: " + ABV.lastCommitHash());
    Log.info("Build git describe: " + ABV.describe());
    Log.info("Build project version: " + ABV.projectVersion());
    Log.info("Built by: '" + ABV.compiledBy() + "'");
    Log.info("Built on: '" + ABV.compiledOn() + "'");

    Runtime runtime = Runtime.getRuntime();
    Log.info("Java availableProcessors: " + runtime.availableProcessors());
    Log.info("Java heap totalMemory: " + PrettyPrint.bytes(runtime.totalMemory()));
    Log.info("Java heap maxMemory: " + PrettyPrint.bytes(runtime.maxMemory()));
    Log.info("Java version: Java "+System.getProperty("java.version")+" (from "+System.getProperty("java.vendor")+")");
    Log.info("OS   version: "+System.getProperty("os.name")+" "+System.getProperty("os.version")+" ("+System.getProperty("os.arch")+")");
  }

  /** Initializes the local node and the local cloud with itself as the only member. */
  private static void startLocalNode() {
    // Figure self out; this is surprisingly hard
    NetworkInit.initializeNetworkSockets();
    // Do not forget to put SELF into the static configuration (to simulate
    // proper multicast behavior)
    if( STATIC_H2OS != null && !STATIC_H2OS.contains(SELF)) {
      Log.warn("Flatfile configuration does not include self: " + SELF+ " but contains " + STATIC_H2OS);
      STATIC_H2OS.add(SELF);
    }

    Log.info ("H2O cloud name: '" + ARGS.name + "' on " + SELF+
              (ARGS.flatfile==null
               ? (", discovery address "+CLOUD_MULTICAST_GROUP+":"+CLOUD_MULTICAST_PORT)
               : ", static configuration based on -flatfile "+ARGS.flatfile));

    Log.info("If you have trouble connecting, try SSH tunneling from your local machine (e.g., via port 55555):\n" +
            "  1. Open a terminal and run 'ssh -L 55555:localhost:"
            + API_PORT + " " + System.getProperty("user.name") + "@" + SELF_ADDRESS.getHostAddress() + "'\n" +
            "  2. Point your browser to http://localhost:55555");


    // Create the starter Cloud with 1 member
    SELF._heartbeat._jar_md5 = 0;//Boot._init._jarHash;
    Paxos.doHeartbeat(SELF);
    assert SELF._heartbeat._cloud_hash != 0;
  }

  public static void main( String[] args ) {
    // Record system start-time.
    if( !START_TIME_MILLIS.compareAndSet(0L, System.currentTimeMillis()) )
      return;                   // Already started

    // Parse args
    new Arguments(args).extract(ARGS);

    // Always print version, whether asked-for or not!
    printAndLogVersion();
    if( ARGS.version ) { exit(0); }
    // Print help & exit
    if( ARGS.help || ARGS.h ) { printHelp(); exit(0); }

    // Get ice path before loading Log or Persist class
    String ice = DEFAULT_ICE_ROOT();
    if( ARGS.ice_root != null ) ice = ARGS.ice_root.replace("\\", "/");
    try {
      ICE_ROOT = new URI(ice);
    } catch(URISyntaxException ex) {
      throw new RuntimeException("Invalid ice_root: " + ice + ", " + ex.getMessage());
    }

    // Epic Hunt for the correct self InetAddress
    NetworkInit.findInetAddressForSelf();

    Log.wrap(); // Wrap stderr

    // Start the local node.  Needed before starting logging.
    startLocalNode();

    String logDir = Log.getLogDir();
    Log.info("Log dir: '"+(logDir==null ? "(unknown)" : Log.getLogDir())+"'");

    

  }

  /** Notify embedding software instance H2O wants to exit.
   *  @param status H2O's requested process exit value.  */
  public static void exit(int status) {
    System.exit(status);
  }
  // Die horribly
  public static void die(String s) {
    System.err.println(s);
    exit(-1);
  }
}
