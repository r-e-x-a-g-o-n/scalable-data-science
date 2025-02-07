// Databricks notebook source
// MAGIC %md
// MAGIC ScaDaMaLe Course [site](https://lamastex.github.io/scalable-data-science/sds/3/x/) and [book](https://lamastex.github.io/ScaDaMaLe/index.html)

// COMMAND ----------

// MAGIC %md
// MAGIC 
// MAGIC # Twitter Hashtag Count
// MAGIC 
// MAGIC Using Twitter Streaming is a great way to learn Spark Streaming if you don't have your streaming datasource and want a great rich input dataset to try Spark Streaming transformations on.
// MAGIC 
// MAGIC In this example, we show how to calculate the top hashtags seen in the last X window of time every Y time unit.
// MAGIC 
// MAGIC ## Top Hashtags in 4 Easy Steps
// MAGIC 
// MAGIC We will now show quickly how to compute the top hashtags in a few easy steps after running some utility functions and importing needed libraries.

// COMMAND ----------

// MAGIC %run "./025_a_extendedTwitterUtils2run"

// COMMAND ----------

import org.apache.spark._
import org.apache.spark.storage._
import org.apache.spark.streaming._


import twitter4j.auth.OAuthAuthorization
import twitter4j.conf.ConfigurationBuilder

// COMMAND ----------

// MAGIC %md
// MAGIC 
// MAGIC **Step 1: Enter your Twitter API Credentials.**
// MAGIC 
// MAGIC * Go to https://apps.twitter.com and look up your Twitter API Credentials, or create an app to create them.
// MAGIC * Run the code in a cell to Enter your own credentials.
// MAGIC 
// MAGIC ```%scala
// MAGIC // put your own twitter developer credentials below instead of xxx
// MAGIC // instead of the '%run ".../secrets/026_secret_MyTwitterOAuthCredentials"' below
// MAGIC // you need to copy-paste the following code-block with your own Twitter credentials replacing XXXX
// MAGIC 
// MAGIC 
// MAGIC // put your own twitter developer credentials below 
// MAGIC 
// MAGIC import twitter4j.auth.OAuthAuthorization
// MAGIC import twitter4j.conf.ConfigurationBuilder
// MAGIC 
// MAGIC 
// MAGIC // These have been regenerated!!! - need to chane them
// MAGIC 
// MAGIC def myAPIKey       = "XXXX" // APIKey 
// MAGIC def myAPISecret    = "XXXX" // APISecretKey
// MAGIC def myAccessToken          = "XXXX" // AccessToken
// MAGIC def myAccessTokenSecret    = "XXXX" // AccessTokenSecret
// MAGIC 
// MAGIC 
// MAGIC System.setProperty("twitter4j.oauth.consumerKey", myAPIKey)
// MAGIC System.setProperty("twitter4j.oauth.consumerSecret", myAPISecret)
// MAGIC System.setProperty("twitter4j.oauth.accessToken", myAccessToken)
// MAGIC System.setProperty("twitter4j.oauth.accessTokenSecret", myAccessTokenSecret)
// MAGIC 
// MAGIC println("twitter OAuth Credentials loaded")
// MAGIC 
// MAGIC ```
// MAGIC 
// MAGIC The cell-below will not expose my Twitter API Credentials: `myAPIKey`, `myAPISecret`, `myAccessToken` and `myAccessTokenSecret`. Use the code above to enter your own credentials in a scala cell.

// COMMAND ----------

// MAGIC %run "Users/raazesh.sainudiin@math.uu.se/scalable-data-science/secrets/026_secret_MyTwitterOAuthCredentials"

// COMMAND ----------

// MAGIC %md
// MAGIC 
// MAGIC **Step 2: Configure where to output the top hashtags and how often to compute them.**

// COMMAND ----------

val outputDirectory = "/datasets/tweetsStreamTmp" // output directory

//Recompute the top hashtags every N seconds. N=1
val slideInterval = new Duration(10 * 1000) // 1000 milliseconds is 1 second!

//Compute the top hashtags for the last M seconds. M=5
val windowLength = new Duration(30 * 1000)

// Wait W seconds before stopping the streaming job. W=100
val timeoutJobLength = 20 * 1000

// COMMAND ----------

// MAGIC %md
// MAGIC 
// MAGIC **Step 3: Run the Twitter Streaming job.**

// COMMAND ----------

// MAGIC %md
// MAGIC Go to SparkUI and see if a streaming job is already running. If so you need to terminate it before starting a new streaming job. Only one streaming job can be run on the DB CE.

// COMMAND ----------

// this will make sure all streaming job in the cluster are stopped
StreamingContext.getActive.foreach{ _.stop(stopSparkContext = false) } 

// COMMAND ----------

// MAGIC %md
// MAGIC Clean up any old files.

// COMMAND ----------

dbutils.fs.mkdirs("dbfs:/datasets/tweetsStreamTmp/")

// COMMAND ----------

display(dbutils.fs.ls("dbfs:/datasets/tweetsStreamTmp/"))

// COMMAND ----------

// MAGIC %md 
// MAGIC Let us write the function that creates the Streaming Context and sets up the streaming job.

// COMMAND ----------

var newContextCreated = false
var num = 0

// This is a helper class used for ordering by the second value in a (String, Int) tuple
import scala.math.Ordering
object SecondValueOrdering extends Ordering[(String, Int)] {
  def compare(a: (String, Int), b: (String, Int)) = {
    a._2 compare b._2
  }
}

// This is the function that creates the SteamingContext and sets up the Spark Streaming job.
def creatingFunc(): StreamingContext = {
  // Create a Spark Streaming Context.
  val ssc = new StreamingContext(sc, slideInterval)
  // Create a Twitter Stream for the input source. 
  val auth = Some(new OAuthAuthorization(new ConfigurationBuilder().build()))
  val twitterStream = ExtendedTwitterUtils.createStream(ssc, auth)
  
  // Parse the tweets and gather the hashTags.
  val hashTagStream = twitterStream.map(_.getText).flatMap(_.split(" ")).filter(_.startsWith("#"))
  
  // Compute the counts of each hashtag by window.
  // reduceByKey on a window of length windowLength
  // Once this is computed, slide the window by slideInterval and calculate reduceByKey again for the second window
  val windowedhashTagCountStream = hashTagStream.map((_, 1)).reduceByKeyAndWindow((x: Int, y: Int) => x + y, windowLength, slideInterval)

  // For each window, calculate the top hashtags for that time period.
  windowedhashTagCountStream.foreachRDD(hashTagCountRDD => {
    val topEndpoints = hashTagCountRDD.top(20)(SecondValueOrdering)
    dbutils.fs.put(s"${outputDirectory}/top_hashtags_${num}", topEndpoints.mkString("\n"), true)
    println(s"------ TOP HASHTAGS For window ${num}")
    println(topEndpoints.mkString("\n"))
    num = num + 1
  })
  
  newContextCreated = true
  ssc
}

// COMMAND ----------

// MAGIC %md 
// MAGIC Create the StreamingContext using getActiveOrCreate, as required when starting a streaming job in Databricks.

// COMMAND ----------

val ssc = StreamingContext.getActiveOrCreate(creatingFunc)

// COMMAND ----------

// MAGIC %md
// MAGIC 
// MAGIC Start the Spark Streaming Context and return when the Streaming job exits or return with the specified timeout.  

// COMMAND ----------

ssc.start()
ssc.awaitTerminationOrTimeout(timeoutJobLength)
ssc.stop(stopSparkContext = false)

// COMMAND ----------

// MAGIC %md
// MAGIC Check out the Clusters `Streaming` UI as the job is running.

// COMMAND ----------

// MAGIC %md
// MAGIC 
// MAGIC It should automatically stop the streaming job after `timeoutJobLength`.
// MAGIC 
// MAGIC If not, then stop any active Streaming Contexts, but don't stop the spark contexts they are attached to using the following command.

// COMMAND ----------

StreamingContext.getActive.foreach { _.stop(stopSparkContext = false) }

// COMMAND ----------

// MAGIC %md
// MAGIC 
// MAGIC **Step 4: View the Results.**

// COMMAND ----------

display(dbutils.fs.ls(outputDirectory))

// COMMAND ----------

// MAGIC %md
// MAGIC There should be 100 intervals for each second and the top hashtags for each of them should be in the file `top_hashtags_N` for `N` in 0,1,2,...,99 and the top hashtags should be based on the past 5 seconds window.

// COMMAND ----------

dbutils.fs.head(s"${outputDirectory}/top_hashtags_0")

// COMMAND ----------

// MAGIC %md
// MAGIC 
// MAGIC ## Let's brainstorm
// MAGIC 
// MAGIC What could you do with this type of streaming capability?
// MAGIC 
// MAGIC * marketing?
// MAGIC * pharmaceutical vigilance?
// MAGIC * linking twitter activity to mass media activity?
// MAGIC * data quality and integrity measures...
// MAGIC 
// MAGIC Note that there are various Spark Streaming ML algorithms that one could easily throw at such `reduceByKeyAndWindow` tweet streams:
// MAGIC 
// MAGIC * [Frequent Pattern Mining](https://spark.apache.org/docs/latest/mllib-frequent-pattern-mining.html)
// MAGIC * [Streaming K-Means](https://databricks.com/blog/2015/01/28/introducing-streaming-k-means-in-spark-1-2.html)
// MAGIC * [Latent Dirichlet Allocation - Topic Modeling](https://spark.apache.org/docs/latest/ml-clustering.html#latent-dirichlet-allocation-lda)
// MAGIC 
// MAGIC Student Project or Volunteer for next Meetup - let's check it out now:
// MAGIC 
// MAGIC HOME-WORK:
// MAGIC 
// MAGIC * [Twitter Streaming Language Classifier](https://databricks.gitbooks.io/databricks-spark-reference-applications/content/twitter_classifier/index.html)

// COMMAND ----------

// MAGIC %md
// MAGIC ## Responsible Experiments
// MAGIC 
// MAGIC Extracting knowledge from tweets is "easy" using techniques shown here, but one has to take responsibility for the use of this knowledge and conform to the rules and policies linked below.
// MAGIC 
// MAGIC Remeber that the use of twitter itself comes with various strings attached. Read:
// MAGIC 
// MAGIC - [Twitter Rules](https://twitter.com/rules)
// MAGIC 
// MAGIC 
// MAGIC Crucially, the use of the content from twitter by you (as done in this worksheet) comes with some strings.  Read:
// MAGIC - [Developer Agreement & Policy Twitter Developer Agreement](https://dev.twitter.com/overview/terms/agreement-and-policy)