import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.jfree.chart.{ChartFactory, ChartPanel}
import org.jfree.chart.plot.PlotOrientation
import org.jfree.data.category.DefaultCategoryDataset
import javax.swing._
import java.awt.event.ActionEvent
import scala.collection.mutable
import scala.jdk.CollectionConverters._

object streaming_all {
  def main(args: Array[String]): Unit = {
    // Initialiser une session Spark
    val spark = SparkSession.builder
      .appName("Streaming CSV Reader")
      .master("local[*]")
      .getOrCreate()

    // Définir le schéma des données CSV
    val schema = StructType(Array(
      StructField("PARAMETER", StringType, true),
      StructField("YEAR", StringType, true),
      StructField("JAN", DoubleType, true),
      StructField("FEB", DoubleType, true),
      StructField("MAR", DoubleType, true),
      StructField("APR", DoubleType, true),
      StructField("MAY", DoubleType, true),
      StructField("JUN", DoubleType, true),
      StructField("JUL", DoubleType, true),
      StructField("AUG", DoubleType, true),
      StructField("SEP", DoubleType, true),
      StructField("OCT", DoubleType, true),
      StructField("NOV", DoubleType, true),
      StructField("DEC", DoubleType, true),
      StructField("ANN", DoubleType, true)
    ))

    // Chemin vers le fichier CSV
    val csvPath = "csv-input/"

    // Lire le fichier CSV en continu avec Spark Structured Streaming
    val df = spark.readStream
      .option("header", "true")
      .schema(schema)
      .csv(csvPath)

    // Filtrer les données pour les années de 1981 à 2022 et PARAMETER == 'T2M'
    val filteredDF = df.filter(col("YEAR").between("1981", "2022") && col("PARAMETER") === "T2M")

    // Créer le dataset pour JFreeChart
    val dataset = new DefaultCategoryDataset()

    // Créer le graphique avec JFreeChart
    val chart = ChartFactory.createBarChart(
      "Température Moyenne Mensuelle",
      "Année",
      "Température (°C)",
      dataset,
      PlotOrientation.VERTICAL,
      true,
      true,
      false
    )

    // Créer le panneau du graphique
    val chartPanel = new ChartPanel(chart)
    chartPanel.setPreferredSize(new java.awt.Dimension(800, 600))

    // Créer le cadre pour afficher le graphique
    val frame = new JFrame("Graphique Température Moyenne")
    frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE)
    frame.getContentPane.add(chartPanel)
    frame.pack()
    frame.setVisible(true)

    // Variables pour stocker les données cumulées
    val cumulativeData = mutable.Map[String, mutable.Map[Int, Double]]()

    // Liste des mois dans l'ordre naturel
    val monthOrder = List("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    // Fonction pour lire les données depuis le fichier CSV
    def readCSVData(): Unit = {
      val staticDF = spark.read
        .option("header", "true")
        .schema(schema)
        .csv(csvPath)
        .filter(col("YEAR").between("1981", "2022") && col("PARAMETER") === "T2M")

      val data = staticDF.collect()
      cumulativeData.clear()

      data.foreach { row =>
        val year = row.getAs[String]("YEAR").toInt
        monthOrder.foreach { month =>
          val temp = row.getAs[Double](month)
          if (!cumulativeData.contains(month)) {
            cumulativeData(month) = mutable.Map[Int, Double]()
          }
          cumulativeData(month)(year) = temp
        }
      }
    }

    // Fonction pour actualiser le graphique
    def updateChart(): Unit = {
      SwingUtilities.invokeLater(new Runnable {
        override def run(): Unit = {
          println("Updating chart...")

          // Réinitialiser le dataset
          dataset.clear()

          // Ajouter les nouvelles données au dataset
          monthOrder.foreach { month =>
            cumulativeData.get(month).foreach { yearTemps =>
              yearTemps.toSeq.sortBy(_._1).foreach { case (year, temp) =>
                dataset.addValue(temp, month, year.toString)
              }
            }
          }

          // Mettre à jour le titre du graphique avec les années dynamiques
          val years = cumulativeData.values.flatMap(_.keys).toSet
          if (years.nonEmpty) {
            val startYear = years.min
            val endYear = years.max
            chart.setTitle(s"Température Moyenne Mensuelle de $startYear à $endYear")
          }

          // Afficher les données du dataset pour vérification
          println("Dataset contents:")
          for (i <- 0 until dataset.getRowCount) {
            for (j <- 0 until dataset.getColumnCount) {
              println(s"${dataset.getRowKey(i)} - ${dataset.getColumnKey(j)}: ${dataset.getValue(i, j)}")
            }
          }

          // Repeindre le graphique pour refléter les changements
          chartPanel.revalidate()
          chartPanel.repaint()
        }
      })
    }

    // Ajouter un bouton pour actualiser le graphique
    val button = new JButton("Actualiser")
    button.addActionListener((e: ActionEvent) => {
      println("Button clicked, updating chart...")

      // Lire les nouvelles données
      readCSVData()

      // Mettre à jour le graphique
      updateChart()
      JOptionPane.showMessageDialog(null, "Le graphique a été actualisé")
    })

    // Ajouter le bouton au cadre
    frame.getContentPane.add(button, java.awt.BorderLayout.SOUTH)

    // Ajouter une requête de traitement pour mettre à jour le graphique
    filteredDF.writeStream.foreachBatch { (batchDF: DataFrame, batchId: Long) =>
      println(s"Processing batch ID: $batchId")

      // Collecter les données du batch
      val data = batchDF.collect()

      println(s"Data collected: ${data.mkString(", ")}")

      // Extraire les années et les températures
      data.foreach { row =>
        val year = row.getAs[String]("YEAR").toInt
        monthOrder.foreach { month =>
          val temp = row.getAs[Double](month)
          if (!cumulativeData.contains(month)) {
            cumulativeData(month) = mutable.Map[Int, Double]()
          }
          cumulativeData(month)(year) = temp
        }
      }

      // Mettre à jour le graphique
      updateChart()
    }.start().awaitTermination()
  }
}
