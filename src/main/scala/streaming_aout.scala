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

object streaming_aout {
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
      StructField("JAN", StringType, true),
      StructField("FEB", StringType, true),
      StructField("MAR", StringType, true),
      StructField("APR", StringType, true),
      StructField("MAY", StringType, true),
      StructField("JUN", StringType, true),
      StructField("JUL", StringType, true),
      StructField("AUG", StringType, true),
      StructField("SEP", StringType, true),
      StructField("OCT", StringType, true),
      StructField("NOV", StringType, true),
      StructField("DEC", StringType, true),
      StructField("ANN", StringType, true)
    ))

    // Chemin vers le fichier CSV
    val csvPath = "csv-input/"

    // Lire le fichier CSV en continu avec Spark Structured Streaming
    val df = spark.readStream
      .option("header", "true")
      .schema(schema)
      .csv(csvPath)

    // Filtrer les données pour les années de 1981 à 2022 et PARAMETER == 'T2M'
    val filteredDF = df.filter(col("YEAR").between("1979", "2023") && col("PARAMETER") === "T2M")

    // Sélectionner les colonnes nécessaires
    val selectedDF = filteredDF.select(col("YEAR"), col("AUG").cast(DoubleType))

    // Créer le dataset pour JFreeChart
    val dataset = new DefaultCategoryDataset()

    // Créer le graphique avec JFreeChart
    val chart = ChartFactory.createBarChart(
      "Température Moyenne en Août",
      "Année",
      "Température Moyenne en Août",
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
    val cumulativeYears = mutable.ArrayBuffer[Int]()
    val cumulativeTemps = mutable.ArrayBuffer[Double]()

    // Fonction pour lire les données depuis le fichier CSV
    def readCSVData(): Unit = {
      val staticDF = spark.read
        .option("header", "true")
        .schema(schema)
        .csv(csvPath)
        .filter(col("YEAR").between("1979", "2023") && col("PARAMETER") === "T2M")
        .select(col("YEAR"), col("AUG").cast(DoubleType))

      val data = staticDF.collect()
      val years = data.map(row => row.getAs[String]("YEAR").toInt).toList
      val temps = data.map(row => row.getAs[Double]("AUG")).toList

      cumulativeYears.clear()
      cumulativeTemps.clear()
      cumulativeYears ++= years
      cumulativeTemps ++= temps
    }

    // Fonction pour actualiser le graphique
    def updateChart(): Unit = {
      SwingUtilities.invokeLater(new Runnable {
        override def run(): Unit = {
          println("Updating chart...")

          // Réinitialiser le dataset
          dataset.clear()

          // Ajouter les nouvelles données au dataset
          cumulativeYears.zip(cumulativeTemps).foreach { case (year, temp) =>
            dataset.addValue(temp, "Température Moyenne", year.toString)
          }

          // Mettre à jour le titre du graphique avec les années dynamiques
          if (cumulativeYears.nonEmpty) {
            val startYear = cumulativeYears.min
            val endYear = cumulativeYears.max
            chart.setTitle(s"Température Moyenne en Août de $startYear à $endYear")
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
    selectedDF.writeStream.foreachBatch { (batchDF: DataFrame, batchId: Long) =>
      println(s"Processing batch ID: $batchId")

      // Collecter les données du batch
      val data = batchDF.collect()

      println(s"Data collected: ${data.mkString(", ")}")

      // Extraire les années et les températures
      val years = data.map(row => row.getAs[String]("YEAR").toInt).toList
      val temps = data.map(row => row.getAs[Double]("AUG")).toList

      // Ajouter les nouvelles données aux données cumulées
      cumulativeYears.clear()
      cumulativeTemps.clear()
      cumulativeYears ++= years
      cumulativeTemps ++= temps

      // Mettre à jour le graphique
      updateChart()
    }.start().awaitTermination()
  }
}
