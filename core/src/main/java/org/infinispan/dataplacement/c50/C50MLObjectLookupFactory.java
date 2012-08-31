package org.infinispan.dataplacement.c50;

import org.infinispan.configuration.cache.Configuration;
import org.infinispan.dataplacement.c50.lookup.BloomFilter;
import org.infinispan.dataplacement.c50.lookup.C50MLTree;
import org.infinispan.dataplacement.keyfeature.AbstractFeature;
import org.infinispan.dataplacement.keyfeature.FeatureValue;
import org.infinispan.dataplacement.keyfeature.KeyFeatureManager;
import org.infinispan.dataplacement.lookup.ObjectLookup;
import org.infinispan.dataplacement.lookup.ObjectLookupFactory;
import org.infinispan.util.TypedProperties;
import org.infinispan.util.Util;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Object Lookup Factory when Machine Learner (C5.0) and Bloom Filters technique is used
 *
 * @author Pedro Ruivo
 * @since 5.2
 */
@SuppressWarnings("UnusedDeclaration") //this is loaded in runtime
public class C50MLObjectLookupFactory implements ObjectLookupFactory {

   public static final String LOCATION = "location";
   public static final String KEY_FEATURE_MANAGER = "keyFeatureManager";
   public static final String BF_FALSE_POSITIVE = "bfFalsePositiveProb";

   private static final String INPUT = File.separator + "input";
   private static final String INPUT_ML_DATA = INPUT + ".data";
   private static final String INPUT_ML_NAMES = INPUT + ".names";

   private static final Log log = LogFactory.getLog(C50MLObjectLookupFactory.class);

   private KeyFeatureManager keyFeatureManager;
   private final Map<String, AbstractFeature> featureMap;

   private String machineLearnerPath = System.getProperty("user.dir");
   private double bloomFilterFalsePositiveProbability = 0.001;

   public C50MLObjectLookupFactory() {
      featureMap = new HashMap<String, AbstractFeature>();
   }

   @Override
   public void setConfiguration(Configuration configuration) {
      TypedProperties typedProperties = configuration.dataPlacement().properties();

      machineLearnerPath = typedProperties.getProperty(LOCATION, machineLearnerPath);
      String keyFeatureManagerClassName = typedProperties.getProperty(KEY_FEATURE_MANAGER, null);

      if (keyFeatureManagerClassName == null) {
         throw new IllegalStateException("Key Feature Manager cannot be null");
      }

      keyFeatureManager = Util.getInstance(keyFeatureManagerClassName, Thread.currentThread().getContextClassLoader());

      if (keyFeatureManager == null) {
         throw new IllegalStateException("Key Feature Manager cannot be null");
      }

      try {
         String tmp =typedProperties.getProperty(BF_FALSE_POSITIVE, "0.001");
         bloomFilterFalsePositiveProbability = Double.parseDouble(tmp);
      } catch (NumberFormatException nfe) {
         log.warnf("Error parsing bloom filter false positive probability. The value is %s. %s",
                   bloomFilterFalsePositiveProbability, nfe.getMessage());
      }

      for (AbstractFeature feature : keyFeatureManager.getAllKeyFeatures()) {
         featureMap.put(feature.getName(), feature);
      }
   }

   @Override
   public ObjectLookup createObjectLookup(Map<Object, Integer> toMoveObj) {
      boolean success = writeObjectsToInputData(toMoveObj);

      if (!success) {
         log.errorf("Cannot create Object Lookup. Error writing input.data");
         return null;
      }

      success = writeInputNames(new TreeSet<Integer>(toMoveObj.values()));

      if (!success) {
         log.errorf("Cannot create Object Lookup. Error writing input.name");
         return null;
      }

      BufferedReader reader = runMachineLearner();

      if (reader == null) {
         log.errorf("Cannot create Object Lookup. Error running the Machine Learner");
         return null;
      }

      Collection<String> rules = getRulesFromMachineLearner(reader);
      
      if (rules.isEmpty()) {
         log.errorf("Cannot create Object Lookup. Machine Learner didn't return any rule");
         return null;
      }

      C50MLTree c50MLTree = new C50MLRulesParser(rules, featureMap).parseMachineLearnerRules();

      BloomFilter bloomFilter = createBloomFilter(toMoveObj.keySet());

      return new C50MLObjectLookup(bloomFilter, c50MLTree, keyFeatureManager);
   }

   @Override
   public Object[] serializeObjectLookup(ObjectLookup objectLookup) {
      if (objectLookup instanceof C50MLObjectLookup) {
         C50MLObjectLookup c50MLObjectLookup = (C50MLObjectLookup) objectLookup;
         return new Object[] {c50MLObjectLookup.bloomFilter, c50MLObjectLookup.c50MLTree};
      }
      return new Object[0];
   }

   @SuppressWarnings("unchecked")
   @Override
   public ObjectLookup deSerializeObjectLookup(Object[] parameters) {
      if (parameters.length == 0) {
         return null;
      }
      BloomFilter bloomFilter = (BloomFilter) parameters[0];
      C50MLTree c50MLTree = (C50MLTree) parameters[1];
      return new C50MLObjectLookup(bloomFilter, c50MLTree, keyFeatureManager);
   }

   public Map<String, AbstractFeature> getFeatureMap() {
      return featureMap;
   }

   public KeyFeatureManager getKeyFeatureManager() {
      return keyFeatureManager;
   }

   /**
    * returns the bloom filter with the objects to move encoding on it
    *
    * @param objectsToMove the objects to move
    * @return              the bloom filter
    */
   private BloomFilter createBloomFilter(Collection<Object> objectsToMove) {
      BloomFilter bloomFilter = new BloomFilter(bloomFilterFalsePositiveProbability, objectsToMove.size());
      for (Object key : objectsToMove) {
         bloomFilter.add(key);
      }
      return bloomFilter;
   }

   /**
    * reads the Machine Learner output and retrieve only the rules part
    *
    * @param reader  the machine learner output
    * @return        each line of the output with the rules
    */
   public final Collection<String> getRulesFromMachineLearner(BufferedReader reader) {
      List<String> rules = new LinkedList<String>();
      boolean beforeTree = true, afterTree = false;
      String line;
      try {
         while ((line = reader.readLine()) != null) {
            if (beforeTree) {
               if (line.startsWith("Decision tree:")) {
                  beforeTree = false;
                  continue;
               }
            } else if (!afterTree) {
               if (line.startsWith("Evaluation")) {
                  afterTree = true;
                  continue;
               }
            }

            if (!beforeTree && !afterTree && !line.isEmpty()) {
               rules.add(line);
            }
         }
      } catch (IOException e) {
         log.errorf("Error reading the Machine Learner rules. %s", e.getMessage());
      }
      return rules;
   }

   /**
    * it starts the machine learner and returns a reader to the output
    *
    * @return  the reader with the machine learner output or null if something went wrong
    */
   private BufferedReader runMachineLearner() {
      try {
         Process process = Runtime.getRuntime()
               .exec(machineLearnerPath + File.separator + "c5.0 -f " + machineLearnerPath + INPUT);
         if (process != null) {
            return new BufferedReader(new InputStreamReader(process.getInputStream()));
         }
      } catch (IOException e) {
         log.errorf("Error starting Machine Learner. %s", e.getMessage());
      }
      return null;
   }

   /**
    * writes the input.name files needed to run the machine leaner
    *
    * @param possibleReturnValues   the possible values of the decision
    * @return                       true if the file was correctly written, false otherwise 
    */
   private boolean writeInputNames(Collection<Integer> possibleReturnValues) {
      BufferedWriter writer = getBufferedWriter(machineLearnerPath + INPUT_ML_NAMES, false);

      if (writer == null) {
         log.errorf("Cannot create writer when tried to write the input.names");
         return false;
      }

      try {
         writer.write("home");
         writer.newLine();
         writer.newLine();

         for (Map.Entry<String, AbstractFeature> entry : featureMap.entrySet()) {
            writeInputNames(writer, entry.getKey(), entry.getValue());
         }

         writer.write("home: ");

         Iterator<Integer> iterator = possibleReturnValues.iterator();

         while (iterator.hasNext()) {
            writer.write(iterator.next().toString());
            if (iterator.hasNext()) {
               writer.write(", ");
            } else {
               writer.write(".");
            }
         }
         writer.flush();

      } catch (IOException e) {
         log.errorf("Error writing input.names. %s", e.getMessage());
         return false;
      }
      close(writer);
      return true;
   }

   /**
    * writes a single feature in the input.names 
    *
    * @param writer        the writer for the file          
    * @param attributeName the feature name
    * @param feature       the feature instance (with type, etc...)
    * @throws IOException  if it cannot write in the file
    */
   private void writeInputNames(BufferedWriter writer, String attributeName, AbstractFeature feature) throws IOException {
      writer.write(attributeName);
      writer.write(": ");
      AbstractFeature.FeatureType type = feature.getType();
      if (type == AbstractFeature.FeatureType.NUMBER) {
         writer.write(type.getNamesFileValue());
      } else {
         Collection<String> listOfNames = feature.getListOfNames();
         if (listOfNames == null) {
            writer.write("ignore.");
         } else {
            Iterator<String> iterator = listOfNames.iterator();

            while (iterator.hasNext()) {
               writer.write(iterator.next().replaceAll("\\s,[.]:[|]", ""));
               if (iterator.hasNext()) {
                  writer.write(", ");
               } else {
                  writer.write(".");
               }
            }
         }
      }
      writer.newLine();
      writer.flush();
   }

   /**
    * writes the input.data with the objects to move and their new owner
    *
    * @param toMoveObj  the objects to move and new location
    * @return           true if the file was correctly wrote, false otherwise
    */
   private boolean writeObjectsToInputData(Map<Object, Integer> toMoveObj) {
      BufferedWriter writer = getBufferedWriter(machineLearnerPath + INPUT_ML_DATA, false);

      if (writer == null) {
         log.errorf("Cannot create writer when tried to write the input.data");
         return false;
      }

      for (Map.Entry<Object, Integer> entry : toMoveObj.entrySet()) {
         try {
            writeInputData(entry.getKey(), entry.getValue(), writer);
         } catch (IOException e) {
            log.errorf("Error writing input.data. %s", e.getMessage());
            return false;
         }
      }

      close(writer);
      return true;
   }

   /**
    * writes a single key in the input.data
    *
    * @param key           the key
    * @param nodeIndex     the new owner index  
    * @param writer        the writer for input.data
    * @throws IOException  if it cannot write on it
    */
   private void writeInputData(Object key, Integer nodeIndex, BufferedWriter writer) throws IOException {
      Map<AbstractFeature, FeatureValue> keyFeatures = keyFeatureManager.getFeatures(key);

      for (AbstractFeature feature : keyFeatureManager.getAllKeyFeatures()) {
         FeatureValue keyFeatureValue = keyFeatures.get(feature);
         String value;
         if (keyFeatureValue == null || keyFeatureValue.getValue() == null) {
            value = "N/A";
         } else {
            value = keyFeatureValue.getValue().toString();
         }
         writer.write(value);
         writer.write(",");
      }
      writer.write(nodeIndex.toString());
      writer.newLine();
      writer.flush();

   }

   /**
    * returns a buffered writer for the file in file path
    *
    * @param filePath   the file path                       
    * @param append     if the writer should append to the file or re-write it
    * @return           the buffered writer or null if the file cannot be written
    */
   private BufferedWriter getBufferedWriter(String filePath, boolean append) {
      try {
         return new BufferedWriter(new FileWriter(filePath, append));
      } catch (IOException e) {
         log.errorf("Cannot create writer for file %s. %s", filePath, e.getMessage());
      }
      return null;
   }

   /**
    * close closeable instance
    *
    * @param closeable  the object to close
    */
   private void close(Closeable closeable) {
      try {
         closeable.close();
      } catch (IOException e) {
         log.warnf("Error closing %s. %s", closeable, e.getMessage());
      }
   }

   /**
    * the object lookup
    */
   private class C50MLObjectLookup implements ObjectLookup {

      private final BloomFilter bloomFilter;
      private final C50MLTree c50MLTree;
      private final KeyFeatureManager keyFeatureManager;

      public C50MLObjectLookup(BloomFilter bloomFilter, C50MLTree c50MLTree,
                               KeyFeatureManager keyFeatureManager) {
         this.bloomFilter = bloomFilter;
         this.c50MLTree = c50MLTree;
         this.keyFeatureManager = keyFeatureManager;
      }

      @Override
      public int query(Object key) {
         if (!bloomFilter.contains(key)) {
            return KEY_NOT_FOUND;
         } else {
            Map<AbstractFeature, FeatureValue> keyFeatures = keyFeatureManager.getFeatures(key);
            int ownerIndex = c50MLTree.getOwnerIndex(keyFeatures);
            if (ownerIndex < 0) {
               return KEY_NOT_FOUND;
            }
            return ownerIndex;
         }
      }
   }
}