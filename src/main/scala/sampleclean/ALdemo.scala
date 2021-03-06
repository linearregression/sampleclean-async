package sampleclean

import org.apache.spark.{SparkContext, SparkConf}

import sampleclean.clean.algorithm.{SampleCleanAlgorithm}
import sampleclean.clean.extraction.FastSplitExtraction
import sampleclean.eval._

import sampleclean.util._
import sampleclean.api.{WorkingSet, SampleCleanContext}
import sampleclean.clean.deduplication._
import org.apache.spark.sql.Row

import org.json4s._
import org.json4s.native.JsonMethods
import org.json4s.native.JsonMethods._

/**
 * This object provides the main driver for the SampleClean
 * application. We execute commands read from the command
 * line.
 */
private [sampleclean] object ALdemo {

  case class UnparsedAlgorithm(name:String,
                               threshold:Option[Double],
                               attribute:Option[String],
                               weighting:Option[Boolean],
                               second_threshold:Option[Double],
                               dedup_columns:Option[List[String]],
                               output_columns:Option[List[String]],
                               delimiter:Option[String]){

    def toSCAlgorithm: (SampleCleanContext,String) => SampleCleanAlgorithm = {
      name match {
        case "shortAttributeER" => EntityResolution.shortAttributeCanonicalize(_,_,attribute.get,threshold.get)
        case "longAttributeER" => EntityResolution.longAttributeCanonicalize(_,_,attribute.get,threshold.get,weighting.get)
        case "activeLearningER" => EntityResolution.textAttributeActiveLearning(_,_,attribute.get,threshold.get,weighting.get)
        case "hybridER" => EntityResolution.hybridAttributeAL(_,_,attribute.get,threshold.get,second_threshold.get,weighting.get)
        case "recordDedup" => RecordDeduplication.deduplication(_,_,dedup_columns.get,threshold.get,weighting.get)
        case "splitExtraction" => FastSplitExtraction.stringSplitAtDelimiter(_,_,attribute.get,delimiter.get,output_columns.get)
        case _ => throw new RuntimeException("Algorithm name not found.")
      }

    }

  }

  case class UnparsedPipeline(pipeline: List[UnparsedAlgorithm])
  case class QueryList(sql_queries: List[String])

  /**
   * Main function
   */
  def main(args: Array[String]) {

    val conf = new SparkConf()
    conf.setAppName("SampleClean Spark Driver")
    conf.setMaster("local[4]")
    conf.set("spark.executor.memory", "4g")
    conf.set("spark.driver.memory", "1g")
    conf.set("spark.storage.memoryFraction", "0.2")

    val sc = new SparkContext(conf)
    val scc = new SampleCleanContext(sc)
    val hiveContext = scc.getHiveContext()
    //scc.closeHiveSession()
    //
    //scc.hql("select * from restaurant_sample_dirty") //cache

    val source = scala.io.Source.fromFile("./src/main/resources/vldb_input_test.json").mkString
    //val source = scala.io.Source.fromFile(args(0)).mkString

    val json = JsonMethods.parse(source)
    implicit val formats = DefaultFormats

    val sampling_ratio = (json \\ "sampling_ratio").values.toString.toDouble

    val dataset:WorkingSet = ((json \\ "dataset").values.toString match {
      case "restaurant" => new CSVLoader(scc, List(("id","String"), ("entity_id","String"), ("name","String"), ("address","String"), ("city","String"), ("type","String")),
        "./src/main/resources/restaurant.csv" )
      case "alcohol" => {
        val alc_cols = List("id","date","convenience_store","store","name","address","city","zipcode") :::
          List("store_location","county_number","county","category","category_name","vendor_no","vendor") :::
            List("item","description","pack","liter_size","state_btl_cost","btl_price","bottle_qty","total")
        new CSVLoader(scc, alc_cols.map(x => (x, "String")),
          "./src/main/resources/alcohol.csv")
      }
    }).load(sampling_ratio)

    //scc.hql("show tables").collect().foreach(println)

    /*val dataset:WorkingSet = ((json \\ "dataset")).values.toString match {
      case "restaurant" => new WorkingSet(scc, "restaurant_sample")
    }*/

    val queries = json.extract[QueryList]

    val algorithms = json.extract[UnparsedPipeline]

    var qmap:Map[String,Double] = Map() 
    queries.sql_queries.foreach { q =>
      println("Query result dirty data: " + q)
      qmap = dataset.query(q).map(x=> (x(0).toString(),x(1).asInstanceOf[Double])).collect().toMap
    }

    val start_time = System.nanoTime()

    for (a <- algorithms.pipeline){
      dataset.clean(a.toSCAlgorithm)
      println("Finished " + a.name + " algorithm")
    }

    val end_time = System.nanoTime()

    queries.sql_queries.foreach { q =>
      println("Query result clean data: " + q)
      dataset.query(q).map(x=> (x(0),x(1).asInstanceOf[Double] - qmap(x(0).toString()))).filter(x => x._2 > 5000).collect().foreach(println)
    }

    println("Execution time in seconds: " + (end_time - start_time) / 1000000000)

    //scc.resetSample("restaurant_sample")
    scc.closeHiveSession()

    //scc.closeHiveSession()
    //println("closed hive session")

  }

}
