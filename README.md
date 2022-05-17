# ANR Supervisor
A system that detects ANRs in the main thread and closes the game before the ANR is reported to Google.
It reports the ANR to our own backend, which stores it in a Database for review.

This alleviates the ANR rate reported by Google Analytics. Most ANRs are caused by Ad SDKs (e.g. Google's own AdMob), so there is an option to only enable it during ad views.

Another solution could be to make a list of problematic devices and not show ads on those devices, but that's a project for another time.

Implementation details:
- The code snippets in PreemptANRs.cs need to be incorporated into yuor own code.
- The Java file goes into your project folder on the path that it's already at.
- StoreReportInCosmosDBUsingPlayFab contains some code that can be used in an Azure Functions App.
