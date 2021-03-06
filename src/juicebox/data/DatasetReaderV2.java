/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2011-2016 Broad Institute, Aiden Lab
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */

package juicebox.data;


import htsjdk.samtools.seekablestream.SeekableHTTPStream;
import htsjdk.samtools.seekablestream.SeekableStream;
import htsjdk.tribble.util.LittleEndianInputStream;
import juicebox.HiC;
import juicebox.HiCGlobals;
import juicebox.tools.utils.original.Preprocessor;
import juicebox.windowui.HiCZoom;
import juicebox.windowui.NormalizationType;
import org.apache.log4j.Logger;
import org.broad.igv.feature.Chromosome;
import org.broad.igv.ui.util.MessageUtils;
import org.broad.igv.util.CompressionUtils;
import org.broad.igv.util.ParsingUtils;
import org.broad.igv.util.stream.IGVSeekableStreamFactory;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;


/**
 * @author jrobinso
 * @date Aug 17, 2010
 */
public class DatasetReaderV2 extends AbstractDatasetReader {

    private static final Logger log = Logger.getLogger(DatasetReaderV2.class);
    /**
     * Cache of chromosome name -> array of restriction sites
     */
    private final Map<String, int[]> fragmentSitesCache = new HashMap<String, int[]>();
    private final CompressionUtils compressionUtils;
    private SeekableStream stream;
    private Map<String, Preprocessor.IndexEntry> masterIndex;
    private Map<String, Preprocessor.IndexEntry> normVectorIndex;
    private Dataset dataset = null;
    private int version = -1;
    private Map<String, FragIndexEntry> fragmentSitesIndex;
    private Map<String, Map<Integer, Preprocessor.IndexEntry>> blockIndexMap;
    private long masterIndexPos;
    private long normVectorFilePosition;

    public DatasetReaderV2(String path) throws IOException {

        super(path);
        this.stream = IGVSeekableStreamFactory.getInstance().getStreamFor(path);

        if (this.stream != null) {
            masterIndex = new HashMap<String, Preprocessor.IndexEntry>();
            dataset = new Dataset(this);
        }
        compressionUtils = new CompressionUtils();
        blockIndexMap = new HashMap<String, Map<Integer, Preprocessor.IndexEntry>>();
    }

    public static String getMagicString(String path) throws IOException {

        SeekableStream stream = null;
        LittleEndianInputStream dis = null;

        try {
            stream = new SeekableHTTPStream(new URL(path)); // IGVSeekableStreamFactory.getStreamFor(path);
            dis = new LittleEndianInputStream(new BufferedInputStream(stream));
        } catch (MalformedURLException e) {
            try {
                dis = new LittleEndianInputStream(new FileInputStream(path));
            }
            catch (Exception e2){
                if(HiCGlobals.guiIsCurrentlyActive){
                    MessageUtils.showErrorMessage("File could not be found\n("+path+")",e2);
                }
            }
        } finally {
            if (stream != null) stream.close();

        }
        if(dis != null) {
            return dis.readString();
        }
        return null;
    }

    private MatrixZoomData readMatrixZoomData(Chromosome chr1, Chromosome chr2, int[] chr1Sites, int[] chr2Sites,
                                              LittleEndianInputStream dis) throws IOException {

        HiC.Unit unit = HiC.valueOfUnit(dis.readString());
        dis.readInt();                // Old "zoom" index -- not used

        // Stats.  Not used yet, but we need to read them anyway
        double sumCounts = (double) dis.readFloat();
        float occupiedCellCount = dis.readFloat();
        float stdDev = dis.readFloat();
        float percent95 = dis.readFloat();

        int binSize = dis.readInt();
        HiCZoom zoom = new HiCZoom(unit, binSize);
        // todo: Default binSize value for "ALL" is 6197...
        // We need to make sure our maps hold a valid binSize value as default.

        int blockBinCount = dis.readInt();
        int blockColumnCount = dis.readInt();

        MatrixZoomData zd = new MatrixZoomData(chr1, chr2, zoom, blockBinCount, blockColumnCount, chr1Sites, chr2Sites, this);

        int nBlocks = dis.readInt();
        HashMap<Integer, Preprocessor.IndexEntry> blockIndex = new HashMap<Integer, Preprocessor.IndexEntry>(nBlocks);

        for (int b = 0; b < nBlocks; b++) {
            int blockNumber = dis.readInt();
            long filePosition = dis.readLong();
            int blockSizeInBytes = dis.readInt();
            blockIndex.put(blockNumber, new Preprocessor.IndexEntry(filePosition, blockSizeInBytes));
        }
        blockIndexMap.put(zd.getKey(), blockIndex);

        int nBins1 = chr1.getLength() / binSize;
        int nBins2 = chr2.getLength() / binSize;
        double avgCount = (sumCounts / nBins1) / nBins2;   // <= trying to avoid overflows
        zd.setAverageCount(avgCount);

        return zd;
    }

    @Override
    public Dataset read() throws IOException {

        try {
            long position = stream.position();

            // Read the header
            LittleEndianInputStream dis = new LittleEndianInputStream(new BufferedInputStream(stream));

            String magicString = dis.readString();
            position += magicString.length() + 1;
            if (!magicString.equals("HIC")) {
                throw new IOException("Magic string is not HIC, this does not appear to be a hic file.");
            }

            version = dis.readInt();
            position += 4;

            System.out.println("HiC file version: " + version);

            masterIndexPos = dis.readLong();
            position += 8;

            String genomeId = dis.readString();
            position += genomeId.length() + 1;

            dataset.setGenomeId(genomeId);

            Map<String, String> attributes = new HashMap<String, String>();
            // Attributes  (key-value pairs)
            if (version > 4) {
                int nAttributes = dis.readInt();
                position += 4;

                for (int i = 0; i < nAttributes; i++) {
                    String key = dis.readString();
                    position += key.length() + 1;

                    String value = dis.readString();
                    position += value.length() + 1;
                    attributes.put(key, value);
                }
            }

            dataset.setAttributes(attributes);

            // Read chromosome dictionary
            int nchrs = dis.readInt();
            position += 4;

            List<Chromosome> chromosomes = new ArrayList<Chromosome>(nchrs);
            for (int i = 0; i < nchrs; i++) {
                String name = dis.readString();
                position += name.length() + 1;

                int size = dis.readInt();
                position += 4;

                chromosomes.add(new Chromosome(i, name, size));
            }
            dataset.setChromosomes(chromosomes);

            int nBpResolutions = dis.readInt();
            position += 4;

            int[] bpBinSizes = new int[nBpResolutions];
            for (int i = 0; i < nBpResolutions; i++) {
                bpBinSizes[i] = dis.readInt();
                position += 4;
            }
            dataset.setBpZooms(bpBinSizes);

            int nFragResolutions = dis.readInt();
            position += 4;

            int[] fragBinSizes = new int[nFragResolutions];
            for (int i = 0; i < nFragResolutions; i++) {
                fragBinSizes[i] = dis.readInt();
                position += 4;
            }
            dataset.setFragZooms(fragBinSizes);

            // Now we need to skip  through stream reading # fragments, stream on buffer is not needed so null it to
            // prevent accidental use
            dis = null;
            if (nFragResolutions > 0) {
                stream.seek(position);
                fragmentSitesIndex = new HashMap<String, FragIndexEntry>();
                Map<String, Integer> map = new HashMap<String, Integer>();
                for (int i = 0; i < nchrs; i++) {
                    String chr = chromosomes.get(i).getName();

                    byte[] buffer = new byte[4];
                    stream.readFully(buffer);
                    int nSites = (new LittleEndianInputStream(new ByteArrayInputStream(buffer))).readInt();
                    position += 4;

                    FragIndexEntry entry = new FragIndexEntry(position, nSites);
                    fragmentSitesIndex.put(chr, entry);
                    map.put(chr, nSites);

                    stream.skip(nSites * 4);
                    position += nSites * 4;
                }
                dataset.setRestrictionEnzyme(map.get(chromosomes.get(1).getName()));
                dataset.setFragmentCounts(map);
            }


            readFooter(masterIndexPos);


        } catch (IOException e) {
            log.error("Error reading dataset", e);
            throw e;
        }


        return dataset;

    }


    public String readStats() throws IOException {
        String statsFileName = path.substring(0, path.lastIndexOf('.')) + "_stats.html";
        String stats = null;
        BufferedReader reader = null;
        try {
            stats = "";
            reader = ParsingUtils.openBufferedReader(statsFileName);
            String nextLine;
            int count = 0; // if there is an big text file that happens to be named the same, don't read it forever
            while ((nextLine = reader.readLine()) != null && count < 1000) {
                stats += nextLine + "\n";
                count++;
            }
        } finally {
            if (reader != null) {
                reader.close();
            }
        }

        return stats;
    }

    private String readGraphs(String graphFileName) throws IOException {
        String graphs = null;
        BufferedReader reader = null;
        try {
            reader = ParsingUtils.openBufferedReader(graphFileName);
            if (reader == null) return null;
            graphs = "";
            String nextLine;
            while ((nextLine = reader.readLine()) != null) {
                graphs += nextLine + "\n";
            }
        } catch (IOException e) {
            System.err.println("Error while reading graphs file: " + e);
            graphs = null;
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
        return graphs;
    }

    private String checkGraphs(String graphs) {
        boolean reset = false;
        if (graphs == null) reset = true;
        else {
            Scanner scanner = new Scanner(graphs);
            try {
                while (!scanner.next().equals("[")) ;

                for (int idx = 0; idx < 2000; idx++) {
                    scanner.nextLong();
                }

                while (!scanner.next().equals("[")) ;
                for (int idx = 0; idx < 201; idx++) {
                    scanner.nextInt();
                    scanner.nextInt();
                    scanner.nextInt();
                }

                while (!scanner.next().equals("[")) ;
                for (int idx = 0; idx < 100; idx++) {
                    scanner.nextInt();
                    scanner.nextInt();
                    scanner.nextInt();
                    scanner.nextInt();
                }
            } catch (NoSuchElementException exception) {
                reset = true;
            }
        }

/*        if (reset) {
            try {
                graphs = readGraphs(null);
            } catch (IOException e) {
                graphs = null;
            }
        }*/
        return graphs;

    }


    private int[] readSites(long location, int nSites) throws IOException {

        stream.seek(location);
        byte[] buffer = new byte[4 + nSites * 4];
        stream.readFully(buffer);
        LittleEndianInputStream les = new LittleEndianInputStream(new ByteArrayInputStream(buffer));
        int[] sites = new int[nSites];
        for (int s = 0; s < nSites; s++) {
            sites[s] = les.readInt();
        }
        return sites;

    }


    @Override
    public int getVersion() {
        return version;
    }

    private void readFooter(long position) throws IOException {

        stream.seek(position);

        //Get the size in bytes of the v5 footer, that is the footer up to normalization and normalized expected values
        byte[] buffer = new byte[4];
        stream.read(buffer);
        LittleEndianInputStream dis = new LittleEndianInputStream(new ByteArrayInputStream(buffer));
        int nBytes = dis.readInt();

        normVectorFilePosition = masterIndexPos + nBytes + 4;  // 4 bytes for the buffer size

        buffer = new byte[nBytes];
        stream.read(buffer);
        dis = new LittleEndianInputStream(new ByteArrayInputStream(buffer));

        int nEntries = dis.readInt();
        for (int i = 0; i < nEntries; i++) {
            String key = dis.readString();
            long filePosition = dis.readLong();
            int sizeInBytes = dis.readInt();
            masterIndex.put(key, new Preprocessor.IndexEntry(filePosition, sizeInBytes));
        }

        Map<String, ExpectedValueFunction> expectedValuesMap = new LinkedHashMap<String, ExpectedValueFunction>();

        // Expected values from non-normalized matrix
        int nExpectedValues = dis.readInt();
        for (int i = 0; i < nExpectedValues; i++) {

            NormalizationType no = NormalizationType.NONE;
            String unitString = dis.readString();
            HiC.Unit unit = HiC.valueOfUnit(unitString);
            int binSize = dis.readInt();
            String key = unitString + "_" + binSize + "_" + no;

            int nValues = dis.readInt();
            double[] values = new double[nValues];
            for (int j = 0; j < nValues; j++) {
                values[j] = dis.readDouble();
            }

            int nNormalizationFactors = dis.readInt();
            Map<Integer, Double> normFactors = new LinkedHashMap<Integer, Double>();
            for (int j = 0; j < nNormalizationFactors; j++) {
                Integer chrIdx = dis.readInt();
                Double normFactor = dis.readDouble();
                normFactors.put(chrIdx, normFactor);
            }

            ExpectedValueFunction df = new ExpectedValueFunctionImpl(no, unit, binSize, values, normFactors);
            expectedValuesMap.put(key, df);
            dataset.setExpectedValueFunctionMap(expectedValuesMap);

        }

        // Normalized expected values (v6 and greater only)

        if (version >= 6) {

            //dis = new LittleEndianInputStream(new BufferedInputStream(stream, 512000));
            dis = new LittleEndianInputStream(new BufferedInputStream(stream, HiCGlobals.bufferSize));

            try {
                nExpectedValues = dis.readInt();
            } catch (EOFException e) {
                if (HiCGlobals.printVerboseComments) {
                    log.info("No normalization vectors");
                }
                return;
            }

            for (int i = 0; i < nExpectedValues; i++) {

                String typeString = dis.readString();
                String unitString = dis.readString();
                HiC.Unit unit = HiC.valueOfUnit(unitString);
                int binSize = dis.readInt();
                String key = unitString + "_" + binSize + "_" + typeString;

                int nValues = dis.readInt();
                double[] values = new double[nValues];
                for (int j = 0; j < nValues; j++) {
                    values[j] = dis.readDouble();
                }

                int nNormalizationFactors = dis.readInt();
                Map<Integer, Double> normFactors = new LinkedHashMap<Integer, Double>();
                for (int j = 0; j < nNormalizationFactors; j++) {
                    Integer chrIdx = dis.readInt();
                    Double normFactor = dis.readDouble();
                    normFactors.put(chrIdx, normFactor);
                }

                NormalizationType type = NormalizationType.valueOf(typeString);
                ExpectedValueFunction df = new ExpectedValueFunctionImpl(type, unit, binSize, values, normFactors);
                expectedValuesMap.put(key, df);

            }

            // Normalization vectors (indexed)

            nEntries = dis.readInt();
            normVectorIndex = new HashMap<String, Preprocessor.IndexEntry>(nEntries * 2);
            for (int i = 0; i < nEntries; i++) {

                NormalizationType type = NormalizationType.valueOf(dis.readString());
                int chrIdx = dis.readInt();
                String unit = dis.readString();
                int resolution = dis.readInt();
                long filePosition = dis.readLong();
                int sizeInBytes = dis.readInt();

                String key = NormalizationVector.getKey(type, chrIdx, unit, resolution);

                dataset.addNormalizationType(type);

                normVectorIndex.put(key, new Preprocessor.IndexEntry(filePosition, sizeInBytes));
            }


        }
    }

    @Override
    public Matrix readMatrix(String key) throws IOException {
        Preprocessor.IndexEntry idx = masterIndex.get(key);
        if (idx == null) {
            return null;
        }

        byte[] buffer = new byte[idx.size];
        stream.seek(idx.position);
        stream.readFully(buffer);
        LittleEndianInputStream dis = new LittleEndianInputStream(new ByteArrayInputStream(buffer));

        int c1 = dis.readInt();
        int c2 = dis.readInt();
        Chromosome chr1 = dataset.getChromosomes().get(c1);
        Chromosome chr2 = dataset.getChromosomes().get(c2);

        // # of resolution levels (bp and frags)
        int nResolutions = dis.readInt();

        List<MatrixZoomData> zdList = new ArrayList<MatrixZoomData>();

        int[] chr1Sites = fragmentSitesCache.get(chr1.getName());
        if (chr1Sites == null && fragmentSitesIndex != null) {
            FragIndexEntry entry = fragmentSitesIndex.get(chr1.getName());
            if (entry != null && entry.nSites > 0) {
                chr1Sites = readSites(entry.position, entry.nSites);
            }
            fragmentSitesCache.put(chr1.getName(), chr1Sites);
        }
        int[] chr2Sites = fragmentSitesCache.get(chr2.getName());
        if (chr2Sites == null && fragmentSitesIndex != null) {
            FragIndexEntry entry = fragmentSitesIndex.get(chr2.getName());
            if (entry != null && entry.nSites > 0) {
                chr2Sites = readSites(entry.position, entry.nSites);
            }
            fragmentSitesCache.put(chr2.getName(), chr2Sites);
        }

        for (int i = 0; i < nResolutions; i++) {
            MatrixZoomData zd = readMatrixZoomData(chr1, chr2, chr1Sites, chr2Sites, dis);
            zdList.add(zd);
        }

        return new Matrix(c1, c2, zdList);
    }

    public int getFragCount(Chromosome chromosome) {
        FragIndexEntry entry = null;
        if (fragmentSitesIndex != null)
            entry = fragmentSitesIndex.get(chromosome.getName());

        if (entry != null) {
            return entry.nSites;
        } else return -1;
    }

    @Override
    synchronized public Block readBlock(int blockNumber, MatrixZoomData zd) throws IOException {

        Block b = null;
        Map<Integer, Preprocessor.IndexEntry> blockIndex = blockIndexMap.get(zd.getKey());
        if (blockIndex != null) {

            Preprocessor.IndexEntry idx = blockIndex.get(blockNumber);
            if (idx != null) {

                //System.out.println(" blockIndexPosition:" + idx.position);

                byte[] compressedBytes = new byte[idx.size];
                stream.seek(idx.position);
                stream.readFully(compressedBytes);
//                System.out.println();
//                System.out.print("ID: ");
//                System.out.print(idx.id);
//                System.out.print(" Pos: ");
//                System.out.print(idx.position);
//                System.out.print(" Size: ");
//                System.out.println(idx.size);
                byte[] buffer;

                try {
                    buffer = compressionUtils.decompress(compressedBytes);

                } catch (Exception e) {
                    throw new RuntimeException("Block read error: " + e.getMessage());
                }

                LittleEndianInputStream dis = new LittleEndianInputStream(new ByteArrayInputStream(buffer));
                int nRecords = dis.readInt();
                List<ContactRecord> records = new ArrayList<ContactRecord>(nRecords);

                if (version < 7) {
                    for (int i = 0; i < nRecords; i++) {
                        int binX = dis.readInt();
                        int binY = dis.readInt();
                        float counts = dis.readFloat();
                        records.add(new ContactRecord(binX, binY, counts));
                    }
                } else {

                    int binXOffset = dis.readInt();
                    int binYOffset = dis.readInt();

                    boolean useShort = dis.readByte() == 0;

                    byte type = dis.readByte();

                    if (type == 1) {
                        // List-of-rows representation
                        int rowCount = dis.readShort();

                        for (int i = 0; i < rowCount; i++) {

                            int binY = binYOffset + dis.readShort();
                            int colCount = dis.readShort();

                            for (int j = 0; j < colCount; j++) {

                                int binX = binXOffset + dis.readShort();
                                float counts = useShort ? dis.readShort() : dis.readFloat();
                                records.add(new ContactRecord(binX, binY, counts));
                            }
                        }
                    } else if (type == 2) {

                        int nPts = dis.readInt();
                        int w = dis.readShort();

                        for (int i = 0; i < nPts; i++) {
                            //int idx = (p.y - binOffset2) * w + (p.x - binOffset1);
                            int row = i / w;
                            int col = i - row * w;
                            int bin1 = binXOffset + col;
                            int bin2 = binYOffset + row;

                            if (useShort) {
                                short counts = dis.readShort();
                                if (counts != Short.MIN_VALUE) {
                                    records.add(new ContactRecord(bin1, bin2, counts));
                                }
                            } else {
                                float counts = dis.readFloat();
                                if (!Float.isNaN(counts)) {
                                    records.add(new ContactRecord(bin1, bin2, counts));
                                }
                            }


                        }

                    } else {
                        throw new RuntimeException("Unknown block type: " + type);
                    }
                }
                b = new Block(blockNumber, records);
            }
        }

        // If no block exists, mark with an "empty block" to prevent further attempts
        if (b == null) {
            b = new Block(blockNumber);
        }
        return b;
    }

    @Override
    public Block readNormalizedBlock(int blockNumber, MatrixZoomData zd, NormalizationType no) throws IOException {


        if (no == null) {
            throw new IOException("Normalization type is null");
        } else if (no == NormalizationType.NONE) {
            return readBlock(blockNumber, zd);
        } else {
            NormalizationVector nv1 = dataset.getNormalizationVector(zd.getChr1Idx(), zd.getZoom(), no);
            NormalizationVector nv2 = dataset.getNormalizationVector(zd.getChr2Idx(), zd.getZoom(), no);
            if (nv1 == null || nv2 == null) {
                throw new IOException("Normalization missing for: " + zd.getKey());
            }
            double[] nv1Data = nv1.getData();
            double[] nv2Data = nv2.getData();
            Block rawBlock = readBlock(blockNumber, zd);
            if (rawBlock == null) return null;

            Collection<ContactRecord> records = rawBlock.getContactRecords();
            List<ContactRecord> normRecords = new ArrayList<ContactRecord>(records.size());
            for (ContactRecord rec : records) {
                int x = rec.getBinX();
                int y = rec.getBinY();
                float counts;
                if (nv1Data[x] != 0 && nv2Data[y] != 0 && !Double.isNaN(nv1Data[x]) && !Double.isNaN(nv2Data[y])) {
                    counts = (float) (rec.getCounts() / (nv1Data[x] * nv2Data[y]));
                } else {
                    counts = Float.NaN;
                }
                normRecords.add(new ContactRecord(x, y, counts));
            }

            //double sparsity = (normRecords.size() * 100) / (Preprocessor.BLOCK_SIZE * Preprocessor.BLOCK_SIZE);
            //System.out.println(sparsity);

            return new Block(blockNumber, normRecords);
        }
    }

    @Override
    public List<Integer> getBlockNumbers(MatrixZoomData zd) {
        Map<Integer, Preprocessor.IndexEntry> blockIndex = blockIndexMap.get(zd.getKey());
        return blockIndex == null ? null : new ArrayList<Integer>(blockIndex.keySet());
    }

    @Override
    public void close() {
        try {
            stream.close();
        } catch (IOException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
    }

    @Override
    public synchronized NormalizationVector readNormalizationVector(NormalizationType type, int chrIdx, HiC.Unit unit, int binSize) throws IOException {

        String key = NormalizationVector.getKey(type, chrIdx, unit.toString(), binSize);
        if (normVectorIndex == null) return null;
        Preprocessor.IndexEntry idx = normVectorIndex.get(key);
        if (idx == null) return null;

        byte[] buffer = new byte[idx.size];
        stream.seek(idx.position);
        stream.readFully(buffer);
        LittleEndianInputStream dis = new LittleEndianInputStream(new ByteArrayInputStream(buffer));

        int nValues = dis.readInt();
        double[] values = new double[nValues];
        boolean allNaN = true;
        for (int i = 0; i < nValues; i++) {
            values[i] = dis.readDouble();
            if (!Double.isNaN(values[i])) {
                allNaN = false;
            }
        }
        if (allNaN) return null;
        else return new NormalizationVector(type, chrIdx, unit, binSize, values);


    }

    public long getNormFilePosition() {
        return version <= 5 ? (new File(this.path)).length() : normVectorFilePosition;
    }

    static class FragIndexEntry {
        final long position;
        final int nSites;

        FragIndexEntry(long position, int nSites) {
            this.position = position;
            this.nSites = nSites;
        }
    }
}
