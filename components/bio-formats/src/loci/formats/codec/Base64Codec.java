//
// Base64Codec.java
//

/*
OME Bio-Formats package for reading and converting biological file formats.
Copyright (C) 2005-@year@ UW-Madison LOCI and Glencoe Software, Inc.

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package loci.formats.codec;

import java.io.IOException;
import loci.formats.FormatException;
import loci.formats.RandomAccessStream;

/**
 * Implements encoding (compress) and decoding (decompress) methods
 * for Base64.  This code was adapted from the Jakarta Commons Codec source,
 * http://jakarta.apache.org/commons
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="https://skyking.microscopy.wisc.edu/trac/java/browser/trunk/components/bio-formats/src/loci/formats/codec/Base64Codec.java">Trac</a>,
 * <a href="https://skyking.microscopy.wisc.edu/svn/java/trunk/components/bio-formats/src/loci/formats/codec/Base64Codec.java">SVN</a></dd></dl>
 *
 * @author Melissa Linkert linkert at wisc.edu
 */
public class Base64Codec extends BaseCodec implements Codec {

  // Base64 alphabet and codes

  private static final byte PAD = (byte) '=';

  private static byte[] base64Alphabet = new byte[255];
  private static byte[] lookupBase64Alphabet = new byte[255];

  static {
    for (int i=0; i<255; i++) {
      base64Alphabet[i] = (byte) -1;
    }
    for (int i = 'Z'; i >= 'A'; i--) {
      base64Alphabet[i] = (byte) (i - 'A');
      lookupBase64Alphabet[i - 'A'] = (byte) i;
    }
    for (int i = 'z'; i >= 'a'; i--) {
      base64Alphabet[i] = (byte) (i - 'a' + 26);
      lookupBase64Alphabet[i - 'a' + 26] = (byte) i;
    }
    for (int i = '9'; i >= '0'; i--) {
      base64Alphabet[i] = (byte) (i - '0' + 52);
      lookupBase64Alphabet[i - '0' + 52] = (byte) i;
    }

    base64Alphabet['+'] = 62;
    base64Alphabet['/'] = 63;

    lookupBase64Alphabet[62] = (byte) '+';
    lookupBase64Alphabet[63] = (byte) '/';
  }

  /* @see Codec#compress(byte[], int, int, int[], Object) */
  public byte[] compress(byte[] input, int x, int y, int[] dims,
    Object options) throws FormatException
  {
    int dataBits = input.length * 8;
    int fewerThan24 = dataBits % 24;
    int numTriples = dataBits / 24;
    byte[] encoded = null;
    int encodedLength = 0;

    if (fewerThan24 != 0) encodedLength = (numTriples + 1) * 4;
    else encodedLength = numTriples * 4;

    encoded = new byte[encodedLength];

    byte k, l, b1, b2, b3;

    int encodedIndex = 0;
    int dataIndex = 0;

    for (int i=0; i<numTriples; i++) {
      dataIndex = i * 3;
      b1 = input[dataIndex];
      b2 = input[dataIndex + 1];
      b3 = input[dataIndex + 2];

      l = (byte) (b2 & 0x0f);
      k = (byte) (b1 & 0x03);

      byte v1 = ((b1 & -128) == 0) ? (byte) (b1 >> 2) :
        (byte) ((b1) >> 2 ^ 0xc0);
      byte v2 = ((b2 & -128) == 0) ? (byte) (b2 >> 4) :
        (byte) ((b2) >> 4 ^ 0xf0);
      byte v3 = ((b3 & -128) == 0) ? (byte) (b3 >> 6) :
        (byte) ((b3) >> 6 ^ 0xfc);

      encoded[encodedIndex] = lookupBase64Alphabet[v1];
      encoded[encodedIndex + 1] = lookupBase64Alphabet[v2 | (k << 4)];
      encoded[encodedIndex + 2] = lookupBase64Alphabet[(l << 2) | v3];
      encoded[encodedIndex + 3] = lookupBase64Alphabet[b3 & 0x3f];
      encodedIndex += 4;
    }

    dataIndex = numTriples * 3;

    if (fewerThan24 == 8) {
      b1 = input[dataIndex];
      k = (byte) (b1 & 0x03);
      byte v = ((b1 & -128) == 0) ? (byte) (b1 >> 2) :
        (byte) ((b1) >> 2 ^ 0xc0);
      encoded[encodedIndex] = lookupBase64Alphabet[v];
      encoded[encodedIndex + 1] = lookupBase64Alphabet[k << 4];
      encoded[encodedIndex + 2] = (byte) '=';
      encoded[encodedIndex + 3] = (byte) '=';
    }
    else if (fewerThan24 == 16) {
      b1 = input[dataIndex];
      b2 = input[dataIndex + 1];
      l = (byte) (b2 & 0x0f);
      k = (byte) (b1 & 0x03);

      byte v1 = ((b1 & -128) == 0) ? (byte) (b1 >> 2) :
        (byte) ((b1) >> 2 ^ 0xc0);
      byte v2 = ((b2 & -128) == 0) ? (byte) (b2 >> 4) :
        (byte) ((b2) >> 4 ^ 0xf0);

      encoded[encodedIndex] = lookupBase64Alphabet[v1];
      encoded[encodedIndex + 1] = lookupBase64Alphabet[v2 | (k << 4)];
      encoded[encodedIndex + 2] = lookupBase64Alphabet[l << 2];
      encoded[encodedIndex + 3] = (byte) '=';
    }

    return encoded;
  }

  /* @see Codec#decompress(RandomAccessStream, Object) */
  public byte[] decompress(RandomAccessStream in, Object options)
    throws FormatException, IOException
  {
    // TODO: Add checks for invalid data.
    if (in.length() == 0) return new byte[0];

    byte b3 = 0, b4 = 0, marker0 = 0, marker1 = 0;

    ByteVector decodedData = new ByteVector();

    byte[] block = new byte[8192];
    in.read(block);
    int p = 0;
    byte b1 = base64Alphabet[block[p++]];
    byte b2 = base64Alphabet[block[p++]];

    while (b1 != -1 && b2 != -1) {
      marker0 = block[p++];
      marker1 = block[p++];

      if (p == block.length) {
        in.read(block);
        p = 0;
      }

      decodedData.add((byte) (b1 << 2 | b2 >> 4));
      if (marker0 != PAD && marker1 != PAD) {
        b3 = base64Alphabet[marker0];
        b4 = base64Alphabet[marker1];

        decodedData.add((byte) (((b2 & 0xf) << 4) | ((b3 >> 2) & 0xf)));
        decodedData.add((byte) (b3 << 6 | b4));
      }
      else if (marker0 == PAD) {
        decodedData.add((byte) 0);
        decodedData.add((byte) 0);
      }
      else if (marker1 == PAD) {
        b3 = base64Alphabet[marker0];

        decodedData.add((byte) (((b2 & 0xf) << 4) | ((b3 >> 2) & 0xf)));
        decodedData.add((byte) 0);
      }
      b1 = base64Alphabet[block[p++]];
      b2 = base64Alphabet[block[p++]];
    }
    return decodedData.toByteArray();
  }

}
