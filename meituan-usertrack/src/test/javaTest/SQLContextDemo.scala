import com.ibeifeng.senior.usertrack.mock.{MockDataUtils, UserInfo}
import com.ibeifeng.senior.usertrack.spark.util.{SQLContextUtil, SparkSessionUtils}
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.hive.HiveContext
import org.apache.spark.{SparkConf, SparkContext}

/**
  * Created by ibf on 10/27.
  */
object SQLContextDemo {
  def main(args: Array[String]): Unit = {
    val conf = new SparkConf()
      .setMaster("local")
      .setAppName("test")
  // val sc = SparkContext.getOrCreate(conf)
//   println( conf.get("spark.app.name"))

    val spark = SparkSessionUtils.getInstance(conf,integratedHive = false)

    MockDataUtils.mockData(spark)

    spark.sql("select * from user_visit_action").show()

    val userInfoDataSavePathStr = "/spark/project/data/mock/user_info"
    val userInfoRDD: RDD[String] = spark.sparkContext.textFile(userInfoDataSavePathStr)
    userInfoRDD.take(10).foreach(println(_))
    // 开始转换为DataFrame并注册成为表
    import spark.implicits._
   val m = userInfoRDD
      .map(line => UserInfo.parseUserInfo(line))
      .filter(_.isDefined)
      .map(_.get)
      .toDF()
      .toDF(UserInfo.columnNames: _*)
      //.createTempView("a")
      .createOrReplaceTempView("a")


//
   spark.sql("select * from a").show()

//    (0 to 10).foreach(i => {
//      // -XX:PermSize=128M -XX:MaxPermSize=128M
//      var sq = new HiveContext(sc)
//      println(s"index:${i}，${new HiveContext(sc)}")
//      println(s"index:${i}，${SQLContextUtil.getInstance(sc,false)}")
//    })

    Thread.sleep(100000)
  }
}
