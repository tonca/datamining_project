package it.unipd.dei.dm1617.death_mining;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import scala.Tuple2;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Created by tonca on 26/05/17.
 *
 * Classe per analizzare sottogruppi (es. Maschi, Femmine, Vecchi, Neri, Suicidi, cose così insomma... )
 *
 * Per selezionare le classi da isolare bisogna passare per linea di comando in ordine:
 * - il nome della colonna (es. Sex, "Binned Age")
 * - il valore (es. M, Adult)
 *
 */
public class SubgroupMining {

    public static void singleGroupMining(JavaSparkContext sc,
                                         JavaRDD<List<Property>> transactions,
                                         String colSelection,
                                         String valSelection,
                                         double minSup
    ) {

        // Removing too frequent items and selecting subgroup
        transactions = transactions.filter( itemset -> {
            boolean contains = false;
            for (Property item : itemset) {
                if (item._1().equals(colSelection) && item._2().equals(valSelection))
                    contains = true;
            }
            return Boolean.valueOf(contains);
        })
                .map(transaction -> {
                    Property reject = null;
                    for (Property item : transaction) {
                        if (item._1().equals(colSelection))
                            reject = item;
                    }
                    transaction.remove(reject);
                    return transaction;
                });

        // mine frequent itemsets and association rules
        DeathMiner dm = new DeathMiner(sc, transactions);
        JavaPairRDD<List<Property>, Double> rddFreqItemAndSupport = dm.mineFrequentItemsets(minSup);
        JavaRDD<ExtendedRule> rddResult = dm.mineAssociationRules();

        // save results in dedicated folder structure
        String subgroupdir = "results/"+colSelection+":"+valSelection+"/";
        String outputdir = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        System.out.println("[saving results] Ouput path: " + outputdir);

        rddResult.sortBy(ExtendedRule::getConfidence, false, 1)
                .map(ExtendedRule::CSVformat)
                .saveAsTextFile(subgroupdir+outputdir + "/rules");

        rddFreqItemAndSupport
                .mapToPair(Tuple2::swap)
                .sortByKey(false, 1)
                .map(i -> i._2.toString() + ";" + i._1)
                .saveAsTextFile(subgroupdir+outputdir + "/freq-itemsets");

    }

    public static void main(String[] args) {

        double sampleProbability = 1;
        double minSup = 0.1;

        SparkConf sparkConf = new SparkConf(true).setAppName("Death Mining");
        JavaSparkContext sc = new JavaSparkContext(sparkConf);

        String filename = "data/DeathRecords.csv";

        // import data
        System.out.println("[read dataset] Sampling with probability " + sampleProbability + " and importing data");
        JavaRDD<List<Property>> transactions = Preprocessing.dataImport(sc, filename, sampleProbability);

        
        Map<String, List<String>> subgroups = new HashMap<>();
        // It can take some time to compute a lot of subgroups together,
        // Comment some of the lines below for a shorter analysis
        subgroups.put("Sex", Arrays.asList("M", "F"));
        subgroups.put("Binned Age", Arrays.asList("Baby", "Child", "Teenager", "Adult", "Old"));
        subgroups.put("RaceRecode3", Arrays.asList("White", "Black", "Races other than White or Black"));

        for(Map.Entry<String, List<String>> entry : subgroups.entrySet())
        {
            for(String val : entry.getValue()) {
                singleGroupMining(sc, transactions, entry.getKey(), val, minSup);
            }
        }
    }
}
