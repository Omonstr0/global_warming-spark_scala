import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.{DataFrame, SparkSession}

import java.io.{BufferedWriter, FileWriter, File}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import scala.concurrent.Await
import scala.concurrent.duration.Duration

object core {
  def main(args: Array[String]): Unit = {
    // Initialiser une session Spark
    Logger.getLogger("org").setLevel(Level.ERROR)

    val spark = SparkSession.builder
      .appName("CSV Reader")
      .master("local[*]")
      .config("spark.sql.repl.eagerEval.enabled", true) // Activer l'évaluation immédiate
      .config("spark.sql.repl.eagerEval.maxNumRows", Int.MaxValue) // Afficher un nombre maximal de lignes
      .getOrCreate()
    spark.sparkContext.setLogLevel("ERROR")
    // Chemin vers le fichier CSV à lire
    val filePath = "temperature_mois_paris.csv"

    // Lire le fichier CSV avec Spark
    val df = spark.read
      .option("header", "true") // Indique que le fichier CSV a un en-tête
      .option("inferSchema", "true") // Infère automatiquement le schéma des données
      .csv(filePath)

    // Afficher le schéma du DataFrame
    df.printSchema()

    // Afficher toutes les lignes du DataFrame
    df.show(df.count.toInt, false) // Afficher toutes les lignes sans tronquer

    // Définir le chemin vers le fichier temporaire
    val tempCsvPath = "csv-input/temp.csv"

    // Fonction pour écrire les données dans temp.csv
    def writeToFile(df: DataFrame, start: Int, batchSize: Int): Future[Unit] = Future {
      val file = new File(tempCsvPath)
      val fileWriter = new FileWriter(file, true)  // true pour ajouter au fichier existant
      val bufferedWriter = new BufferedWriter(fileWriter)

      // On s'assure de ne pas dépasser le nombre total de lignes du DataFrame
      try {
        val end = math.min(start + batchSize, df.count().toInt)
        for (i <- start until end) {
          bufferedWriter.write(df.collect()(i).mkString(",") + "\n")
        }
        bufferedWriter.flush() // Forcer l'écriture sur le disque
      } finally {
        bufferedWriter.close()
      }
    }

    // Fonction pour lire et afficher le contenu de temp.csv
    def printTempCsvContent(): Unit = {
      println(s"\nContenu de $tempCsvPath :")
      val bufferedSource = scala.io.Source.fromFile(tempCsvPath)
      for (line <- bufferedSource.getLines) {
        println(line)
      }
      bufferedSource.close()
    }

    // Écrire dans temp.csv toutes les 5 secondes
    val batchSize = 5
    val totalRows = df.count().toInt
    var start = 0

    val interval = 5.seconds // Intervalle de temps entre chaque écriture

    val writeTask = Future {
      while (start < totalRows) {
        Await.result(writeToFile(df, start, batchSize), Duration.Inf)
        printTempCsvContent()
        start += batchSize
        Thread.sleep(interval.toMillis)
      }
    }

    // Gérer la fin de l'écriture
    writeTask.onComplete {
      case Success(_) => println("Writing to temp.csv completed")
      case Failure(exception) => println(s"Error writing to temp.csv: ${exception.getMessage}")
    }

    // Attendre que toutes les tâches soient terminées avant de fermer Spark
    try {
      Await.result(writeTask, Duration.Inf)
    } finally {
      // Fermer la session Spark
      spark.stop()
    }
  }
}
