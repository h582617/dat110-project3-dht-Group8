package no.hvl.dat110.util;

/**
 * exercise/demo purpose in dat110
 * @author tdoy
 *
 */

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Hash { 
	
	
	public static BigInteger hashOf(String entity) {	
		
		BigInteger hashint = null;
		
		// Task: Hash a given string using MD5 and return the result as a BigInteger.
		try {
		// we use MD5 with 128 bits digest
		MessageDigest md = MessageDigest.getInstance("MD5");
		// compute the hash of the input 'entity'
		byte[] entityBytes = entity.getBytes();
		md.update(entityBytes);
		byte[] digest = md.digest();
		// convert the hash into hex format
		String value = toHex(digest);
		// convert the hex into BigInteger
		hashint = new BigInteger(value,16);
		// return the BigInteger
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}
		return hashint;
	  }
	
	public static BigInteger addressSize() {
		MessageDigest md;
		// Task: compute the address size of MD5
		try {
			md = MessageDigest.getInstance("MD5");
		
		// compute the number of bits = bitSize()
		int length = md.getDigestLength();
		int numBit = length*8;
		// compute the address size = 2 ^ number of bits
		BigInteger svar = new BigInteger("2").pow(numBit);
		// return the address size
		return svar;
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}
		return null;
	}
	
	public static int bitSize() {
		
		int digestlen = 0;
		try {
		digestlen = MessageDigest.getInstance("MD5").getDigestLength();
	} catch (NoSuchAlgorithmException e) {
		e.printStackTrace();
	}
		// find the digest length
		
		return digestlen*8;
	}
	
	public static String toHex(byte[] digest) {
		StringBuilder strbuilder = new StringBuilder();
		for(byte b : digest) {
			strbuilder.append(String.format("%02x", b&0xff));
		}
		return strbuilder.toString();
	}

}
