namespace StoryGiant
{
	public class CosmosDBTableEntry
	{
		public string id;
		public string PlayFabID;
		public string Timestamp;
	}

	public class ANRReport : CosmosDBTableEntry
	{
		public string TitleId;
		public object Report;
	}

	public static class CloudFunctions
	{
		const string DATABASE_ID = "xxxxxxxxx";
		const string DATABASE_URL = "DATABASE_URL";
		const string DATABASE_PRIMARY_KEY = "DATABASE_PRIMARY_KEY";
		const string ANR_REPORTS_TABLE = "anr_reports";

		public static async Task StoreObjectInDB<T>(string containerId, T item) where T : CosmosDBTableEntry
		{
			var timestamp = $"{DateTime.UtcNow:o}";
			item.id = $"{item.PlayFabID} {timestamp}";
			item.Timestamp = timestamp;

			var endpointUri = Environment.GetEnvironmentVariable(DATABASE_URL, EnvironmentVariableTarget.Process);
			var primaryKey = Environment.GetEnvironmentVariable(DATABASE_PRIMARY_KEY, EnvironmentVariableTarget.Process);
			var cosmosClient = new CosmosClient(endpointUri, primaryKey);

			Container container;
			bool create = true; // For now. Remove when names are settled.
			if (create)
			{
				var database = (await cosmosClient.CreateDatabaseIfNotExistsAsync(DATABASE_ID)).Database;
				container = (await database.CreateContainerIfNotExistsAsync(containerId, "/PlayFabID")).Container;
			}
			else
			{
				container = cosmosClient.GetDatabase(DATABASE_ID).GetContainer(containerId);
			}
			await container.UpsertItemAsync(item, new PartitionKey(item.PlayFabID));
		}

		[FunctionName("reportANR")]
		public static async Task<IActionResult> RunReportANR([HttpTrigger(AuthorizationLevel.Anonymous, GET, POST, Route = null)] HttpRequestMessage req, ILogger log)
		{
			var context = new Context(req, log);
			try
			{
				await context.Create();
				context.Parameters.TryGetValue("report", out var reportObject);

				await StoreObjectInDB(ANR_REPORTS_TABLE, new ANRReport
				{
					id = $"{context.CurrentPlayerId} {DateTime.UtcNow:o}",
					PlayFabID = context.CurrentPlayerId,
					Timestamp = $"{DateTime.UtcNow:o}",

					TitleId = context.TitleInfo.Name,
					Report = reportObject,
				});
			}
			catch (Exception e)
			{
				return new ExceptionResult(log, e);
			}
			return new OkObjectResult(OK);
		}
	}
}
