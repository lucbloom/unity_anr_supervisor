//*
import android.os.Handler;
import android.os.Looper;
import com.google.firebase.crashlytics.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

// A class supervising the UI thread for ANR errors. Use 
// {@link #start()} and {@link #stop()} to control
// when the UI thread is supervised
public class ANRSupervisor
{
	static ANRSupervisor instance;

	public static Logger logger = Logger.getLogger("ANR");
	public static void Log(Object log) { logger.log(Level.INFO, "com.gamehouse.tx [ANR] " + log); }

	// The {@link ExecutorService} checking the UI thread
	private ExecutorService mExecutor;

	// The {@link ANRSupervisorRunnable} running on a separate thread
	public final ANRSupervisorRunnable mSupervisorRunnable;

	public ANRSupervisor(Looper looper, int timeoutCheckDuration, int checkInterval)
	{
		mExecutor = Executors.newSingleThreadExecutor();
		mSupervisorRunnable = new ANRSupervisorRunnable(looper, timeoutCheckDuration, checkInterval);
	}
	
	public static void create()
	{
		if (instance == null)
		{
			// Check for misbehaving SDKs on the main thread.
			ANRSupervisor.Log("Creating Main Thread Supervisor");
			instance = new ANRSupervisor(Looper.getMainLooper(), 1, 5);
		}

		// Why bother? // Check for misbehaving Script code on the Unity thread.
		// Why bother? ANRSupervisor.Log("Creating Unity Supervisor");
		// Why bother? ANRSupervisor unitySupervisor = new ANRSupervisor(Looper.myLooper(), 5, 8);
	}

	// Starts the supervision
	public static synchronized void start()
	{
		synchronized (instance.mSupervisorRunnable)
		{
			ANRSupervisor.Log("Starting Supervisor");
			if (instance.mSupervisorRunnable.isStopped())
			{
				instance.mExecutor.execute(instance.mSupervisorRunnable);
			}
			else
			{
				instance.mSupervisorRunnable.resume();
			}
		}
	}

	// Stops the supervision. The stop is delayed, so if start() is called right after stop(),
	// both methods will have no effect. There will be at least one more ANR check before the supervision is stopped.
	public static synchronized void stop()
	{
		instance.mSupervisorRunnable.stop();
	}
	
	public static String getReport()
	{
		if (instance != null &&
			instance.mSupervisorRunnable != null &&
			instance.mSupervisorRunnable.mReport != null)
		{
			String report = instance.mSupervisorRunnable.mReport;
			instance.mSupervisorRunnable.mReport = null;
			return report;
		}
		return null;
	}

	public static synchronized void generateANROnMainThreadTEST()
	{
		ANRSupervisor.Log("Creating mutext locked infinite thread");
		new Thread(new Runnable() {
			@Override public void run() {
				synchronized (instance) {
					while (true) {
						ANRSupervisor.Log("Sleeping for 60 seconds");
						try {
							Thread.sleep(60*1000);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
				}
			}
		}).start();

		ANRSupervisor.Log("Running a callback on the main thread that tries to lock the mutex (but can't)");
		new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
			@Override public void run() {
				ANRSupervisor.Log("Trying to lock the mutex");
				synchronized (instance) {
					ANRSupervisor.Log("This shouldn't happen");
					throw new IllegalStateException();
				}
			}
		}, 1000);

		ANRSupervisor.Log("End of generateANROnMainThreadTEST");
	}
}

// A {@link Runnable} testing the UI thread every 5 seconds until {@link #stop()} is called
class ANRSupervisorRunnable implements Runnable
{
	// The {@link Handler} to access the UI threads message queue
	private Handler mHandler;

	// The stop flag
	private boolean mStopped;

	// Flag indicating the stop was performed
	private boolean mStopCompleted = true;

	private int mTimeoutCheck;
	private int mCheckInterval;
	
	public String mReport;

	public ANRSupervisorRunnable(Looper looper, int timeoutCheckDuration, int checkInterval)
	{
		ANRSupervisor.Log("Installing ANR Suparvisor on " + looper + " timeout: " + timeoutCheckDuration);
		mHandler = new Handler(looper);
		mTimeoutCheck = timeoutCheckDuration;
		mCheckInterval = checkInterval;
	}

	@Override public void run()
	{
		this.mStopCompleted = false;

		// Loop until stop() was called or thread is interrupted
		while (!Thread.interrupted())
		{
			try
			{
				//ANRSupervisor.Log("Sleeping for " + mCheckInterval + " seconds until next test");
				Thread.sleep(mCheckInterval * 1000);

				ANRSupervisor.Log("Check for ANR...");

				// Create new callback
				ANRSupervisorCallback callback = new ANRSupervisorCallback();

				// Perform test, Handler should run the callback within X seconds
				synchronized (callback)
				{
					this.mHandler.post(callback);
					callback.wait(mTimeoutCheck * 1000);

					// Check if called
					if (!callback.isCalled())
					{
						ANRSupervisor.Log("Thread " + this.mHandler.getLooper() + " DID NOT respond within " + mTimeoutCheck + " seconds");

						String report = getProcessJson(this.mHandler.getLooper().getThread());
						ANRSupervisor.Log(report);

						ANRSupervisor.Log("Sending log to Firebase");
						//FirebaseCrash.report(e);
						FirebaseCrashlytics.getInstance().log(report);
						
						mReport = report;

						// Go into waiting mode until the thread responds.
						//callback.wait();

						ANRSupervisor.Log("Waiting another 4 seconds for the report to come through...");
						callback.wait(4000);

						if (!callback.isCalled())
						{
							ANRSupervisor.Log("Killing myself");
							// If the supervised thread still did not respond, quit the app.
							android.os.Process.killProcess(android.os.Process.myPid());

							ANRSupervisor.Log("Exiting the app");
							System.exit(1);
						}
					}
					else
					{
						//ANRSupervisor.Log("Thread " + this.mHandler.getLooper() + " responded within " + mTimeoutCheck + " seconds");
					}
				}

				// Check if stopped
				this.checkStopped();
			}
			catch (InterruptedException e)
			{
				ANRSupervisor.Log("Interruption caught.");
				break;
			}
		}

		// Set stop completed flag
		this.mStopCompleted = true;

		ANRSupervisor.Log("supervision stopped");
	}

	public String getProcessJson(Thread supervisedThread)
	{
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		PrintStream ps = new PrintStream(bos);

		// Get all stack traces in the system
		Map<Thread, StackTraceElement[]> stackTraces = Thread.getAllStackTraces();
		Locale l = Locale.getDefault();
		
		String deviceName = "";
		try
		{
			android.content.ContentResolver cr = com.unity3d.player.UnityPlayer.currentActivity.getApplicationContext().getContentResolver();
			deviceName = android.provider.Settings.Secure.getString(cr, "device_name");
			if (deviceName == null || deviceName.length() <= 0)
			{
				deviceName = android.provider.Settings.Secure.getString(cr, "bluetooth_name");
			}
		}
		catch (Exception e) {}

		ps.print(String.format(l, "{\"title\":\"ANR Report\",\"version\":\"%s\",\"device\":\"%s\",\"name\":\"%s\",\"callstacks\":[",
			String.valueOf(BuildConfig.VERSION_NAME), android.os.Build.FINGERPRINT, deviceName));

		Object[] objs = stackTraces.keySet().toArray();
		Thread[] threads = new Thread[objs.length];
		for (int j = 0; j < objs.length; ++j) { threads[j] = (Thread)objs[j]; }
		Arrays.sort(threads, Comparator.comparing(Thread::getState));

		for (int t = 0; t < threads.length; ++t)
		{
			Thread thread = threads[t];
			ps.print(String.format(l, "{\"name\":\"%s\",\"state\":\"%s\"", thread.getName(), thread.getState()));
			
			if (thread == supervisedThread)
			{
				ps.print(",\"supervised\":true");
			}

			StackTraceElement[] stack = stackTraces.get(thread);
			if (stack.length > 0)
			{
				ps.print(",\"stack\":[");
				for (int i = 0; i < stack.length; ++i)
				{
					StackTraceElement element = stack[i];
					ps.print(String.format(l, "{\"func\":\"%s.%s\",\"file\":\"%s\",\"line\":%d}",
							element.getClassName(),
							element.getMethodName(),
							element.getFileName(), 
							element.getLineNumber()));
					if (i < stack.length-1) { ps.print(","); }
				}
				ps.print("]");
			}
			ps.print("}");
			if (t < threads.length-1) { ps.print(","); }
		}
		ps.print("]}");

		return new String(bos.toByteArray());
	}

	private synchronized void checkStopped() throws InterruptedException
	{
		if (this.mStopped)
		{
			// Wait 1 second
			Thread.sleep(1000);

			// Break if still stopped
			if (this.mStopped)
			{
				throw new InterruptedException();
			}
		}
	}

	synchronized void stop()
	{
		ANRSupervisor.Log("Stopping...");
		this.mStopped = true;
	}

	synchronized void resume()
	{
		ANRSupervisor.Log("Resuming...");
		this.mStopped = false;
	}

	synchronized boolean isStopped() { return this.mStopCompleted; }
}

// A {@link Runnable} which calls {@link #notifyAll()} when run.
class ANRSupervisorCallback implements Runnable
{
	private boolean mCalled;

	public ANRSupervisorCallback() { super(); }

	@Override public synchronized void run()
	{
		this.mCalled = true;
		this.notifyAll();
	}

	synchronized boolean isCalled() { return this.mCalled; }
}
//*/
