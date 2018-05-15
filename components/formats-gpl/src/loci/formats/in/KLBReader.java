/*
 * #%L
 * OME Bio-Formats package for reading and converting biological file formats.
 * %%
 * Copyright (C) 2005 - 2018 Open Microscopy Environment:
 *   - Board of Regents of the University of Wisconsin-Madison
 *   - Glencoe Software, Inc.
 *   - University of Dundee
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the 
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public 
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
package loci.formats.in;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import loci.common.CBZip2InputStream;
import loci.common.RandomAccessInputStream;
import loci.formats.CoreMetadata;
import loci.formats.FormatException;
import loci.formats.FormatReader;
import loci.formats.FormatTools;
import loci.formats.codec.CodecOptions;
import loci.formats.codec.ZlibCodec;
import loci.formats.meta.MetadataStore;

import ome.units.UNITS;

import ome.xml.model.primitives.PositiveFloat;

/**
 * Reader for Keller Lab Block (KLB) files.
 */
public class KLBReader extends FormatReader {

  // -- Constants --
  private static final int KLB_DATA_DIMS = 5; //images have at most 5 dimensions: x,y,z, c, t
  private static final int KLB_METADATA_SIZE = 256; //number of bytes in metadata
  private static final int KLB_DEFAULT_HEADER_VERSION = 2;

  private static final int UINT8_TYPE = 0;
  private static final int UINT16_TYPE = 1;
  private static final int UINT32_TYPE = 2;
  private static final int UINT64_TYPE = 3;
  private static final int INT8_TYPE = 4;
  private static final int INT16_TYPE = 5;
  private static final int INT32_TYPE = 6;
  private static final int INT64_TYPE = 7;
  private static final int FLOAT32_TYPE = 8;
  private static final int FLOAT64_TYPE = 9;

  // Compression formats
  private static final int COMPRESSION_NONE = 0;
  private static final int COMPRESSION_BZIP2 = 1;
  private static final int COMPRESSION_ZLIB = 2;
  
  // -- Fields --

  private MetadataStore store;

  private int compressionType = 0;
  private double numBlocks = 1;
  private int[] dims_blockSize = new int[KLB_DATA_DIMS];
  private int[] dims_xyzct = new int[KLB_DATA_DIMS];
  private long[] blockOffsets;

  private long headerSize;
  private int blocksPerPlane;
  private long offsetFilePointer;
  private int headerVersion;
  
  // -- Constructor --

  /**
   * Constructs a new BDV reader.
   */
  public KLBReader() {
    super("KLB", "klb");
    suffixSufficient = true;
    domains = new String[] {FormatTools.UNKNOWN_DOMAIN};
  }

  // -- IFormatReader API methods --

  /**
   * @see loci.formats.IFormatReader#openBytes(int, byte[], int, int, int, int)
   */
  @Override
  public byte[] openBytes(int no, byte[] buf, int x, int y, int w, int h)
    throws FormatException, IOException
  {
    FormatTools.checkPlaneParameters(this, no, buf.length, x, y, w, h);
    
    //As number of offsets can be greater than INT_MAX only storing enough required for given plane
    //New offsets are read from header each time openBytes is called
    reCalculateBlockOffsets(no);
    
    int bytesPerPixel = FormatTools.getBytesPerPixel(getPixelType());
    int blockSizeBytes = bytesPerPixel;
    
    int[]dimsBlock = new int[KLB_DATA_DIMS]; //Number of blocks on each dimension
    int[] coordBlock = new int[KLB_DATA_DIMS]; //Coordinates
    int[] blockSizeAux = new int[KLB_DATA_DIMS]; //Block sizes taking into account border cases

    for (int ii = 0; ii < KLB_DATA_DIMS; ii++)
    {
      blockSizeBytes *= dims_blockSize[ii];
      dimsBlock[ii] = (int) Math.ceil((float)dims_xyzct[ii] / (float)dims_blockSize[ii]);
    }

    long compressedBlockSize = blockOffsets[1] - blockOffsets[0];
    int firstBlock = 0;
    int outputOffset = 0;

    for (int xx=0; xx < blocksPerPlane; xx++) {

      //calculate coordinate (in block space)
      int blockIdx_aux = xx;
      for (int ii = 0; ii < KLB_DATA_DIMS; ii++)
      {
        coordBlock[ii] = blockIdx_aux % dimsBlock[ii];
        blockIdx_aux -= coordBlock[ii];
        blockIdx_aux /= dimsBlock[ii];
        coordBlock[ii] *= dims_blockSize[ii];//parsing coordinates to image space (not block anymore)
        
      }
 
      compressedBlockSize = xx == 0 ? blockOffsets[0] : blockOffsets[xx] - blockOffsets[xx-1];
      long offset =  xx == 0 ? 0 : blockOffsets[firstBlock + xx - 1];

      //Seek to start of block
      in.seek((long) (headerSize + offset));
        
      //Read compressed block
      byte[] block = new byte[(int) compressedBlockSize];
      in.read(block);

      //Decompress block
      if (compressionType == COMPRESSION_BZIP2) {
        // Discard first two bytes of BZIP2 header
        byte[] tempPixels = block;
        block = new byte[tempPixels.length - 2];
        System.arraycopy(tempPixels, 2, block, 0, block.length);

        try {
          ByteArrayInputStream bais = new ByteArrayInputStream(block);
          CBZip2InputStream bzip = new CBZip2InputStream(bais);
          block = new byte[blockSizeBytes];
          bzip.read(block, 0, block.length);
          tempPixels = null;
          bais.close();
          bzip.close();
          bais = null;
          bzip = null;
        }
        catch(IOException e) {
          LOGGER.debug("IOException while decompressing block {}", xx);
          throw e;
        }
      }
      else if (compressionType == COMPRESSION_ZLIB) {
        CodecOptions options = new CodecOptions();
        block = new ZlibCodec().decompress(block, options);
      }

      // Calculate block size in case we had border block        
      for (int ii = 0; ii < KLB_DATA_DIMS; ii++) {
        blockSizeAux[ii] = Math.min(dims_blockSize[ii], (dims_xyzct[ii] - coordBlock[ii]));
      }

      try {
        int imageRowSize = dims_xyzct[0] * bytesPerPixel;
        int blockRowSize = blockSizeAux[0] * bytesPerPixel;

        // Location in output buffer to copy block
        outputOffset = (imageRowSize * coordBlock[1]) + (coordBlock[0] * bytesPerPixel);

        // Location within the block for required XY plane
        int inputOffset = (coordBlock[2] % dims_blockSize[2]) * blockRowSize * blockSizeAux[1];
        inputOffset += (coordBlock[3] % dims_blockSize[3]) * blockRowSize * blockSizeAux[1] * blockSizeAux[2];
        inputOffset += (coordBlock[4] % dims_blockSize[4]) * blockRowSize * blockSizeAux[1] * blockSizeAux[2] * blockSizeAux[3];

        // Copy row at a time from decompressed block to output buffer
        for (int numRows = 0; numRows < blockSizeAux[1]; numRows++) {
          System.arraycopy(block, inputOffset + (numRows * blockRowSize), buf, outputOffset + (numRows * imageRowSize), blockRowSize);
        }
      }
      catch(Exception e) {
        throw new FormatException("Exception caught while copying decompressed block data to output buffer");
      }
    }

    return buf;
  }

  // -- Internal FormatReader API methods --

  /* @see loci.formats.FormatReader#initFile(String) */
  @Override
  protected void initFile(String id) throws FormatException, IOException {
    super.initFile(id);
    store = makeFilterMetadata();
    in = new RandomAccessInputStream(id);
    readHeader();
  }

  private void readHeader() throws IOException, FormatException {
    headerVersion = in.readUnsignedByte();
    CoreMetadata ms0 = core.get(0);
    ms0.littleEndian = true;
    for (int i=0; i < KLB_DATA_DIMS; i++) {
      dims_xyzct[i] = readUInt32();
    }
    ms0.dimensionOrder = "XYZCT";

    ms0.sizeX = dims_xyzct[0];
    ms0.sizeY = dims_xyzct[1];
    ms0.sizeZ = dims_xyzct[2];
    ms0.sizeC = dims_xyzct[3];
    ms0.sizeT = dims_xyzct[4];
    ms0.imageCount = getSizeZ() * getSizeC() * getSizeT();

    PositiveFloat[] dims_pixelSize = new PositiveFloat[KLB_DATA_DIMS];
    for (int i=0; i < KLB_DATA_DIMS; i++) {
      dims_pixelSize[i] = readFloat32();
    }
    store.setPixelsPhysicalSizeX(FormatTools.createLength(dims_pixelSize[0], UNITS.MICROMETER), 0);
    store.setPixelsPhysicalSizeY(FormatTools.createLength(dims_pixelSize[1], UNITS.MICROMETER), 0);
    store.setPixelsPhysicalSizeZ(FormatTools.createLength(dims_pixelSize[2], UNITS.MICROMETER), 0);

    int dataType = in.readUnsignedByte();
    convertPixelType(ms0, dataType);

    compressionType = in.readUnsignedByte();
    byte[] user_metadata = new byte[KLB_METADATA_SIZE];
    in.read(user_metadata);

    for (int i=0; i < KLB_DATA_DIMS; i++) {
      dims_blockSize[i] = readUInt32();
    }
    blocksPerPlane = (int) (Math.ceil((float)getSizeX()/dims_blockSize[0]) * Math.ceil((float)getSizeY()/dims_blockSize[1]));
    for (int i=0; i < KLB_DATA_DIMS; i++) {
      numBlocks *= Math.ceil((float)(dims_xyzct[i]) / (float)(dims_blockSize[i]));
    }

    headerSize = (long) ((KLB_DATA_DIMS * 12) + 2 + (numBlocks * 8) + KLB_METADATA_SIZE + 1);
    blockOffsets = new long[blocksPerPlane];

    offsetFilePointer = in.getFilePointer();
    for (int i=0; i < blocksPerPlane; i++) {
      blockOffsets[i] = readUInt64();
    }
  }
  
  // Needed as offsets array can only be int max and full image may be greater
  private void reCalculateBlockOffsets(int no) throws IOException, FormatException {
    String order = core.get(getSeries()).dimensionOrder;
    int[] ztc = FormatTools.getZCTCoords(order, getSizeZ(), getSizeC(), getSizeT(), getImageCount(), no);

    // Calculate the first required block
    int requiredBlockNum = (ztc[0] / dims_blockSize[2]) * (ztc[2] / dims_blockSize[3]) * (ztc[1] / dims_blockSize[4]);

    // Mark the current file pointer to return after reading offsets
    long filePoointer = in.getFilePointer();

    // Seek to start of offsets and read required offsets
    in.seek(offsetFilePointer + (requiredBlockNum * blocksPerPlane * 8));
    for (int i=0; i < blocksPerPlane; i++) {
      blockOffsets[i] = readUInt64();
    }
    in.seek(filePoointer);
  }

  // Helper methods

  private void convertPixelType(CoreMetadata ms0, int pixelType) throws FormatException {
    switch (pixelType) {
      case UINT8_TYPE:
        ms0.pixelType = FormatTools.UINT8;
        break;
      case UINT16_TYPE:
        ms0.pixelType = FormatTools.UINT16;
        break;
      case UINT32_TYPE:
        ms0.pixelType = FormatTools.UINT32;
        break;
      case UINT64_TYPE:
      case INT64_TYPE:
        ms0.pixelType = FormatTools.DOUBLE;
        break;
      case INT8_TYPE:
        ms0.pixelType = FormatTools.INT8;
        break;
      case INT16_TYPE:
        ms0.pixelType = FormatTools.INT16;
        break;
      case INT32_TYPE:
        ms0.pixelType = FormatTools.INT32;
        break;
      case FLOAT32_TYPE:
      case FLOAT64_TYPE:
        ms0.pixelType = FormatTools.FLOAT;
        break;
      default:
        throw new FormatException("Unknown pixel type: " + pixelType);
    }
    ms0.interleaved = ms0.rgb;
  }

  private int readUInt32() throws IOException {
    byte[] b = new byte[4];
    in.read(b, 0, 4);
    ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
    return bb.getInt();
  }

  private long readUInt64() throws IOException {
    byte[] b = new byte[8];
    in.read(b, 0, 8);
    ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
    return bb.getLong();
  }

  private PositiveFloat readFloat32() throws IOException {
    byte[] b = new byte[4];
    in.read(b, 0, 4);
    ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
    return new PositiveFloat((double) bb.getFloat());
  }

}
