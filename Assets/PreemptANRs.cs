#define UNITY_ANDROID // remove this line

class Game
{
	public OnStartup()
	{
#if UNITY_ANDROID// && ANR_SUPERVISOR
        var ANRSupervisor = new AndroidJavaClass("ANRSupervisor");
        ANRSupervisor.CallStatic("create");

        // Uncomment if ANRSupervisor should always run, not just during ads.
        ANRSupervisor.CallStatic("start");
#endif // UNITY_ANDROID
	}

    // If your reporting is done in C#, you can use this function to grab the report.
    void Update()
    {
#if UNITY_ANDROID// && ANR_SUPERVISOR
        var ANRSupervisor = new AndroidJavaClass("ANRSupervisor");
        var anrReport = ANRSupervisor.CallStatic<string>("getReport");
        if (!anrReport.IsNullOrEmpty())
        {
            PlayFabSimpleJson.TryDeserializeObject(anrReport, out var obj);
            SGDebug.LogR(LogTag.System, $"Reporting ANR throught PlayFab: {obj}");
            PlayFabManager.Instance.ExeCloudScript("reportANR", new Dictionary<string, object> {
                {"report", obj ?? anrReport},
            }, res => {
                ANRSupervisor.CallStatic("reportSent");
            });
        }
#endif // UNITY_ANDROID
    }

    public void OnTestButtonClicked()
    {
#if UNITY_ANDROID// && ANR_SUPERVISOR
        // Make sure the Supervisor is running
        var ANRSupervisor = new AndroidJavaClass("ANRSupervisor");
        ANRSupervisor.CallStatic("start");

        // Generate an ANR on the main Java thread
        ANRSupervisor.CallStatic("generateANROnMainThreadTEST");

        // Test the reporting function
        //var anrReport = new ANRReport() { callstacks = new List<string>() {
        //    "A",
        //    "B",
        //} };
        //PlayFabManager.Instance.ExeCloudScript("reportANR", new Dictionary<string, object> {
        //    {"report", anrReport},
        //});

        // Generate an ANR on the Unity thread (but that would not cause a Google ANR)
        //for (var i = 0; i < 60; ++i) // ANR!
        //{
        //    System.Threading.Thread.Sleep(1000); // ANR!
        //}
#endif // UNITY_ANDROID
    }
}

class AdHandler
{
    public void ShowAd()
    {
#if UNITY_ANDROID// && ANR_SUPERVISOR
        // Make sure the ANRSupervisor is running during ad views
        var ANRSupervisor = new AndroidJavaClass("ANRSupervisor");
        ANRSupervisor.CallStatic("start");
#endif // UNITY_ANDROID
    }

    private void FinalizeAdSequence()
    {
#if UNITY_ANDROID// && ANR_SUPERVISOR
        // Uncomment if ANRSupervisor should only run during ads.
        var ANRSupervisor = new AndroidJavaClass("ANRSupervisor");
        ANRSupervisor.CallStatic("stop");
#endif // UNITY_ANDROID
    }
}
