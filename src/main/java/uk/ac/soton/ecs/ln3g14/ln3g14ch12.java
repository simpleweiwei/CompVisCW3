package uk.ac.soton.ecs.ln3g14;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.openimaj.data.DataSource;
import org.openimaj.data.dataset.Dataset;
import org.openimaj.data.dataset.GroupedDataset;
import org.openimaj.data.dataset.ListDataset;
import org.openimaj.data.dataset.VFSListDataset;
import org.openimaj.experiment.dataset.sampling.GroupSampler;
import org.openimaj.experiment.dataset.sampling.GroupedUniformRandomisedSampler;
import org.openimaj.experiment.dataset.split.GroupedRandomSplitter;
import org.openimaj.experiment.evaluation.classification.ClassificationEvaluator;
import org.openimaj.experiment.evaluation.classification.ClassificationResult;
import org.openimaj.experiment.evaluation.classification.analysers.confusionmatrix.CMAnalyser;
import org.openimaj.experiment.evaluation.classification.analysers.confusionmatrix.CMResult;
import org.openimaj.feature.DiskCachingFeatureExtractor;
import org.openimaj.feature.DoubleFV;
import org.openimaj.feature.FeatureExtractor;
import org.openimaj.feature.SparseIntFV;
import org.openimaj.feature.local.data.LocalFeatureListDataSource;
import org.openimaj.feature.local.list.LocalFeatureList;
import org.openimaj.image.FImage;
import org.openimaj.image.ImageUtilities;
import org.openimaj.image.annotation.evaluation.datasets.Caltech101;
import org.openimaj.image.annotation.evaluation.datasets.Caltech101.Record;
import org.openimaj.image.feature.dense.gradient.dsift.ByteDSIFTKeypoint;
import org.openimaj.image.feature.dense.gradient.dsift.DenseSIFT;
import org.openimaj.image.feature.dense.gradient.dsift.PyramidDenseSIFT;
import org.openimaj.image.feature.local.aggregate.BagOfVisualWords;
import org.openimaj.image.feature.local.aggregate.BlockSpatialAggregator;
import org.openimaj.image.feature.local.aggregate.PyramidSpatialAggregator;
import org.openimaj.io.IOUtils;
import org.openimaj.ml.annotation.linear.LiblinearAnnotator;
import org.openimaj.ml.annotation.linear.LiblinearAnnotator.Mode;
import org.openimaj.ml.clustering.ByteCentroidsResult;
import org.openimaj.ml.clustering.assignment.HardAssigner;
import org.openimaj.ml.clustering.kmeans.ByteKMeans;
import org.openimaj.ml.kernel.HomogeneousKernelMap;
import org.openimaj.ml.kernel.HomogeneousKernelMap.KernelType;
import org.openimaj.ml.kernel.HomogeneousKernelMap.WindowType;
import org.openimaj.util.pair.IntFloatPair;

import de.bwaldvogel.liblinear.SolverType;

public class ln3g14ch12 {
	public static void main( String[] args ) throws Exception {
		ln3g14ch12 c = new ln3g14ch12();

		c.tutorial();
//		c.ex1();
//		c.ex2();
//		c.ex3();
	}

	/*
	 * Code from Tutorial page
	 *
	 * Example results
	 *   Accuracy: 0.733
	 * Error Rate: 0.267
	 */
	void tutorial() throws Exception {
		// Create dataset from Caltech101 data
		System.out.println("creating dataset");
		GroupedDataset<String, VFSListDataset<Record<FImage>>, Record<FImage>> allData = Caltech101.getData(ImageUtilities.FIMAGE_READER);

		// Creating subset of dataset to minimise run time
		System.out.println("creating subset dataset");
		GroupedDataset<String, ListDataset<Record<FImage>>, Record<FImage>> data = GroupSampler.sample(allData, 5, false);

		// Create training and testing data
		System.out.println("splitting subset");
		GroupedRandomSplitter<String, Record<FImage>> splits = new GroupedRandomSplitter<String, Record<FImage>>(data, 15, 0, 15);

		// Creating SIFT extractor
		System.out.println("creating SIFT extractor");
		DenseSIFT dsift = new DenseSIFT(5, 7);
		PyramidDenseSIFT<FImage> pdsift = new PyramidDenseSIFT<FImage>(dsift, 6f, 7);

		// Creating assigner using trainQuantiser method
		System.out.println("creating assigner");
		HardAssigner<byte[], float[], IntFloatPair> assigner = trainQuantiser(GroupedUniformRandomisedSampler.sample(splits.getTrainingDataset(), 30), pdsift);

		// Creating extractor using PHOWExtractor class
		System.out.println("creating PHOW extractor");
		FeatureExtractor<DoubleFV, Record<FImage>> extractor = new PHOWExtractor(pdsift, assigner);

		// Train classifier
		System.out.println("training classifier");
		LiblinearAnnotator<Record<FImage>, String> ann = new LiblinearAnnotator<Record<FImage>, String>(extractor, Mode.MULTICLASS, SolverType.L2R_L2LOSS_SVC, 1.0, 0.00001);
		ann.train(splits.getTrainingDataset());

		// Evaluate classifier accuracy
		System.out.println("testing classifier");
		ClassificationEvaluator<CMResult<String>, String, Record<FImage>> eval =
				new ClassificationEvaluator<CMResult<String>, String, Record<FImage>>(ann, splits.getTestDataset(), new CMAnalyser<Record<FImage>, String>(CMAnalyser.Strategy.SINGLE));
		Map<Record<FImage>, ClassificationResult<String>> guesses = eval.evaluate();
		CMResult<String> result = eval.analyse(guesses);
		System.out.println(result);
	}

	static HardAssigner<byte[], float[], IntFloatPair> trainQuantiser(Dataset<Record<FImage>> sample, PyramidDenseSIFT<FImage> pdsift) {
		List<LocalFeatureList<ByteDSIFTKeypoint>> allkeys = new ArrayList<LocalFeatureList<ByteDSIFTKeypoint>>();
		for (Record<FImage> rec : sample) {
			FImage img = rec.getImage();
			pdsift.analyseImage(img);
			allkeys.add(pdsift.getByteKeypoints(0.005f));
		}
		if (allkeys.size() > 10000)
			allkeys = allkeys.subList(0, 10000);
		ByteKMeans km = ByteKMeans.createKDTreeEnsemble(300);
		DataSource<byte[]> datasource = new LocalFeatureListDataSource<ByteDSIFTKeypoint, byte[]>(allkeys);
		ByteCentroidsResult result = km.cluster(datasource);
		return result.defaultHardAssigner();
	}

	static class PHOWExtractor implements FeatureExtractor<DoubleFV, Record<FImage>> {
		PyramidDenseSIFT<FImage> pdsift;
		HardAssigner<byte[], float[], IntFloatPair> assigner;
		public PHOWExtractor(PyramidDenseSIFT<FImage> pdsift, HardAssigner<byte[], float[], IntFloatPair> assigner) {
			this.pdsift = pdsift;
			this.assigner = assigner;
		}
		public DoubleFV extractFeature(Record<FImage> object) {
			FImage image = object.getImage();
			pdsift.analyseImage(image);
			BagOfVisualWords<byte[]> bovw = new BagOfVisualWords<byte[]>(assigner);
			BlockSpatialAggregator<byte[], SparseIntFV> spatial = new BlockSpatialAggregator<byte[], SparseIntFV>(bovw, 2, 2);
			return spatial.aggregate(pdsift.getByteKeypoints(0.015f), image.getBounds()).normaliseFV();
		}
	}

	/*
	 * Apply a Homogeneous Kernel Map
	 */
	void ex1() throws IOException {
		// Create dataset from Caltech101 data
		System.out.println("creating dataset");
		GroupedDataset<String, VFSListDataset<Record<FImage>>, Record<FImage>> allData = Caltech101.getData(ImageUtilities.FIMAGE_READER);

		// Creating subset of dataset to minimise run time
		System.out.println("creating subset dataset");
		GroupedDataset<String, ListDataset<Record<FImage>>, Record<FImage>> data = GroupSampler.sample(allData, 5, false);

		// Create training and testing data
		System.out.println("splitting subset");
		GroupedRandomSplitter<String, Record<FImage>> splits = new GroupedRandomSplitter<String, Record<FImage>>(data, 15, 0, 15);

		// Creating SIFT extractor
		System.out.println("creating SIFT extractor");
		DenseSIFT dsift = new DenseSIFT(5, 7);
		PyramidDenseSIFT<FImage> pdsift = new PyramidDenseSIFT<FImage>(dsift, 6f, 7);

		// Creating assigner using trainQuantiser method
		System.out.println("creating assigner");
		HardAssigner<byte[], float[], IntFloatPair> assigner = trainQuantiser(GroupedUniformRandomisedSampler.sample(splits.getTrainingDataset(), 30), pdsift);

		// Creating extractor using PHOWExtractor class
		System.out.println("creating PHOW extractor");
		FeatureExtractor<DoubleFV, Record<FImage>> extractor = new PHOWExtractor(pdsift, assigner);

		// using HomogeneousKernelMap
		System.out.println("creating HomogeneousKernelMap");
		HomogeneousKernelMap map = new HomogeneousKernelMap(KernelType.Chi2, WindowType.Rectangular);
		map.createWrappedExtractor(extractor);

		// Train classifier
		System.out.println("training classifier");
		LiblinearAnnotator<Record<FImage>, String> ann = new LiblinearAnnotator<Record<FImage>, String>(extractor, Mode.MULTICLASS, SolverType.L2R_L2LOSS_SVC, 1.0, 0.00001);
		ann.train(splits.getTrainingDataset());

		// Evaluate classifier accuracy
		System.out.println("testing classifier");
		ClassificationEvaluator<CMResult<String>, String, Record<FImage>> eval =
				new ClassificationEvaluator<CMResult<String>, String, Record<FImage>>(ann, splits.getTestDataset(), new CMAnalyser<Record<FImage>, String>(CMAnalyser.Strategy.SINGLE));
		Map<Record<FImage>, ClassificationResult<String>> guesses = eval.evaluate();
		CMResult<String> result = eval.analyse(guesses);
		System.out.println(result);
	}

	/*
	 * Feature Caching
	 * Reading and writing to file between steps
	 */
	void ex2() throws IOException {
		// Create dataset from Caltech101 data
		System.out.println("creating dataset");
		GroupedDataset<String, VFSListDataset<Record<FImage>>, Record<FImage>> allData = Caltech101.getData(ImageUtilities.FIMAGE_READER);

		// Creating subset of dataset to minimise run time
		System.out.println("creating subset dataset");
		GroupedDataset<String, ListDataset<Record<FImage>>, Record<FImage>> data = GroupSampler.sample(allData, 5, false);

		// Create training and testing data
		System.out.println("splitting subset");
		GroupedRandomSplitter<String, Record<FImage>> splits = new GroupedRandomSplitter<String, Record<FImage>>(data, 15, 0, 15);

		// Creating SIFT extractor
		System.out.println("creating SIFT extractor");
		DenseSIFT dsift = new DenseSIFT(5, 7);
		PyramidDenseSIFT<FImage> pdsift = new PyramidDenseSIFT<FImage>(dsift, 6f, 7);

		// Creating assigner using trainQuantiser method
		System.out.println("creating assigner");
		HardAssigner<byte[], float[], IntFloatPair> assigner = trainQuantiser(GroupedUniformRandomisedSampler.sample(splits.getTrainingDataset(), 30), pdsift);

		// Writing to file
		File f = new File("assigner");
		IOUtils.writeToFile(assigner, f);
	
		// Reading from file
		HardAssigner<byte[], float[], IntFloatPair> readAssigner = IOUtils.readFromFile(f);
		
		// Creating extractor using PHOWExtractor class
		System.out.println("creating PHOW extractor");
		FeatureExtractor<DoubleFV, Record<FImage>> extractor = new PHOWExtractor(pdsift, readAssigner);

		// Extracting features
		System.out.println("extracting features");
		LiblinearAnnotator<Record<FImage>, String> ann = new LiblinearAnnotator<Record<FImage>, String>(extractor, Mode.MULTICLASS, SolverType.L2R_L2LOSS_SVC, 1.0, 0.00001);
		
		// Saving features to disk
		System.out.println("Saving features to disk");
//		Files.write(Paths.get("/out"), content.getBytes(), StandardOpenOption.CREATE);
		DiskCachingFeatureExtractor dcfe = new DiskCachingFeatureExtractor(null, extractor);
		
		// Training classifier
		System.out.println("training classifier");
		ann.train(splits.getTrainingDataset());

		// Evaluate classifier accuracy
		System.out.println("testing classifier");
		ClassificationEvaluator<CMResult<String>, String, Record<FImage>> eval =
				new ClassificationEvaluator<CMResult<String>, String, Record<FImage>>(ann, splits.getTestDataset(), new CMAnalyser<Record<FImage>, String>(CMAnalyser.Strategy.SINGLE));
		Map<Record<FImage>, ClassificationResult<String>> guesses = eval.evaluate();
		CMResult<String> result = eval.analyse(guesses);
		System.out.println(result);
	}

	/*
	 * The whole dataset
	 * ~40mins for training
	 * ~35mins for testing
	 *   Accuracy: 0.266
	 * Error Rate: 0.734
	 */
	void ex3() throws IOException {
		// Create dataset from Caltech101 data
		System.out.println("creating dataset");
		GroupedDataset<String, VFSListDataset<Record<FImage>>, Record<FImage>> allData = Caltech101.getData(ImageUtilities.FIMAGE_READER);

		// Create training and testing data
		System.out.println("splitting subset");
		GroupedRandomSplitter<String, Record<FImage>> splits = new GroupedRandomSplitter<String, Record<FImage>>(allData, 15, 0, 15);

		// Creating SIFT extractor
		System.out.println("creating SIFT extractor");
		DenseSIFT dsift = new DenseSIFT(3, 7);
		PyramidDenseSIFT<FImage> pdsift = new PyramidDenseSIFT<FImage>(dsift, 6f, 4);

		// Creating assigner using trainQuantiser method
		System.out.println("creating assigner");
		HardAssigner<byte[], float[], IntFloatPair> assigner = trainQuantiser(GroupedUniformRandomisedSampler.sample(splits.getTrainingDataset(), 30), pdsift);

		// Creating extractor using PHOWExtractor class
		System.out.println("creating PHOW extractor");
		FeatureExtractor<DoubleFV, Record<FImage>> extractor = new PHOWExtractorPyramid(pdsift, assigner);

		// Train classifier
		System.out.println("training classifier");
		LiblinearAnnotator<Record<FImage>, String> ann = new LiblinearAnnotator<Record<FImage>, String>(extractor, Mode.MULTICLASS, SolverType.L2R_L2LOSS_SVC, 1.0, 0.00001);
		ann.train(splits.getTrainingDataset());

		// Evaluate classifier accuracy
		System.out.println("testing classifier");
		ClassificationEvaluator<CMResult<String>, String, Record<FImage>> eval =
				new ClassificationEvaluator<CMResult<String>, String, Record<FImage>>(ann, splits.getTestDataset(), new CMAnalyser<Record<FImage>, String>(CMAnalyser.Strategy.SINGLE));
		Map<Record<FImage>, ClassificationResult<String>> guesses = eval.evaluate();
		CMResult<String> result = eval.analyse(guesses);
		System.out.println(result);
	}
	
	static class PHOWExtractorPyramid implements FeatureExtractor<DoubleFV, Record<FImage>> {
		PyramidDenseSIFT<FImage> pdsift;
		HardAssigner<byte[], float[], IntFloatPair> assigner;
		public PHOWExtractorPyramid(PyramidDenseSIFT<FImage> pdsift, HardAssigner<byte[], float[], IntFloatPair> assigner) {
			this.pdsift = pdsift;
			this.assigner = assigner;
		}
		public DoubleFV extractFeature(Record<FImage> object) {
			FImage image = object.getImage();
			pdsift.analyseImage(image);
			BagOfVisualWords<byte[]> bovw = new BagOfVisualWords<byte[]>(assigner);
			PyramidSpatialAggregator<byte[], SparseIntFV> spatial = new PyramidSpatialAggregator<byte[], SparseIntFV>(bovw, 2, 2);
			return spatial.aggregate(pdsift.getByteKeypoints(0.015f), image.getBounds()).normaliseFV();
		}
	}
}
