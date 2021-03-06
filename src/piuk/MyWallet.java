/*
 * Copyright 2011-2012 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package piuk;

import android.util.Base64;
import org.spongycastle.util.encoders.Hex;
import com.google.bitcoin.core.Base58;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.Wallet;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.json.simple.JSONValue;
import org.json.simple.parser.JSONParser;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

import org.spongycastle.crypto.BufferedBlockCipher;
import org.spongycastle.crypto.CipherParameters;
import org.spongycastle.crypto.PBEParametersGenerator;
import org.spongycastle.crypto.engines.AESEngine;
import org.spongycastle.crypto.generators.PKCS5S2ParametersGenerator;
import org.spongycastle.crypto.modes.CBCBlockCipher;
import org.spongycastle.crypto.paddings.BlockCipherPadding;
import org.spongycastle.crypto.paddings.ISO10126d2Padding;
import org.spongycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.spongycastle.crypto.params.KeyParameter;
import org.spongycastle.crypto.params.ParametersWithIV;

public class MyWallet {
	private static final int AESBlockSize = 4;
	public static final int DefaultPBKDF2Iterations = 10;
	public Map<String, Object> root;
	public String temporyPassword;
	public String temporySecondPassword;

	public static final NetworkParameters params = NetworkParameters.prodNet();

	public MyWallet(String base64Payload, String password) throws Exception {
		this.root = decryptPayload(base64Payload, password);

		if (root == null)
			throw new Exception("Error Decrypting Wallet");
	}

	// Create a new Wallet
	public MyWallet() throws Exception {
		this.root = new HashMap<String, Object>();

		root.put("guid", UUID.randomUUID().toString());
		root.put("sharedKey", UUID.randomUUID().toString());

		List<Map<String, Object>> keys = new ArrayList<Map<String, Object>>();
		List<Map<String, Object>> address_book = new ArrayList<Map<String, Object>>();

		root.put("keys", keys);
		root.put("address_book", address_book);

		addKey(new ECKey(), "New");
	}

	@SuppressWarnings("unchecked")
	public List<Map<String, Object>> getKeysMap() {
		return (List<Map<String, Object>>) root.get("keys");
	}

	public String[] getActiveAddresses() {
		List<String> list = new ArrayList<String>();
		for (Map<String, Object> map : getKeysMap()) {
			if (map.get("tag") == null || (Long) map.get("tag") == 0)
				list.add((String) map.get("addr"));
		}
		return list.toArray(new String[list.size()]);
	}

	public String[] getAllAddresses() {
		List<String> list = new ArrayList<String>();
		for (Map<String, Object> map : getKeysMap()) {
			list.add((String) map.get("addr"));
		}
		return list.toArray(new String[list.size()]);
	}

	public String[] getArchivedAddresses() {
		List<String> list = new ArrayList<String>();
		for (Map<String, Object> map : getKeysMap()) {
			if (map.get("tag") != null && (Long) map.get("tag") == 2)
				list.add((String) map.get("addr"));
		}
		return list.toArray(new String[list.size()]);
	}

	@SuppressWarnings("unchecked")
	public List<Map<String, Object>> getAddressBookMap() {
		return (List<Map<String, Object>>) root.get("address_book");
	}

	@SuppressWarnings("unchecked")
	public Map<String, String> getTxNotes() {
		Map<String, String> tx_notes = (Map<String, String>) root.get("tx_notes");

		if (tx_notes == null) {
			tx_notes = Collections.emptyMap();

			root.put("tx_notes", tx_notes);
		}

		return tx_notes;
	}

	public boolean addTxNote(Hash hash, String note) throws Exception {
		//Disallow quotes and < >
		if (StringUtils.containsAny(note, "\"'<>")) {
			throw new Exception("Note contains invalid characters");
		}

		getTxNotes().put(hash.toString(), note);	

		return true;
	}

	public int getFeePolicy() {
		Map<String, Object> options = getOptions();

		int fee_policy = 0;
		if (options.containsKey("fee_policy")) {
			fee_policy = Integer.valueOf(options.get("fee_policy").toString());
		}

		return fee_policy;
	}


	@SuppressWarnings("unchecked")
	public Map<String, Object> getOptions() {
		Map<String, Object> options = (Map<String, Object>) root.get("options");

		if (options == null) {
			options = Collections.emptyMap();

			root.put("options", options);
		}

		return options;
	}

	public int getPbkdf2Iterations() {
		Map<String, Object> options = getOptions();

		int iterations = DefaultPBKDF2Iterations;
		if (options.containsKey("pbkdf2_iterations")) {
			iterations = Integer.valueOf(options.get("pbkdf2_iterations").toString());
		}

		return iterations;
	}

	public boolean isDoubleEncrypted() {
		Object double_encryption = root.get("double_encryption");
		if (double_encryption != null)
			return (Boolean) double_encryption;
		else
			return false;
	}

	public String getGUID() {
		return (String) root.get("guid");
	}

	public String getSharedKey() {
		return (String) root.get("sharedKey");
	}

	public String getDPasswordHash() {
		return (String) root.get("dpasswordhash");
	}

	public void setTemporyPassword(String password) {
		this.temporyPassword = password;
	}

	public String getTemporyPassword() {
		return temporyPassword;
	}

	public String getTemporySecondPassword() {
		return temporySecondPassword;
	}

	public void setTemporySecondPassword(String secondPassword) {
		this.temporySecondPassword = secondPassword;
	}

	public String toJSONString() {
		return JSONValue.toJSONString(root);
	}

	public String getPayload() throws Exception {
		return encrypt(toJSONString(), this.temporyPassword, DefaultPBKDF2Iterations);
	}

	public static ECKey decodeBase58PK(String base58Priv) throws Exception {
		byte[] privBytes = Base58.decode(base58Priv);

		// Prppend a zero byte to make the biginteger unsigned
		byte[] appendZeroByte = ArrayUtils.addAll(new byte[1], privBytes);

		ECKey ecKey = new ECKey(new BigInteger(appendZeroByte));

		return ecKey;
	}

	public static ECKey decodeBase64PK(String base64Priv) throws Exception {
		byte[] privBytes = Base64.decode(base64Priv, Base64.NO_PADDING);

		// Prppend a zero byte to make the biginteger unsigned
		byte[] appendZeroByte = ArrayUtils.addAll(new byte[1], privBytes);

		ECKey ecKey = new ECKey(new BigInteger(appendZeroByte));

		return ecKey;
	}


	public static ECKey decodeHexPK(String hex) throws Exception {
		byte[] privBytes = Hex.decode(hex);

		// Prppend a zero byte to make the biginteger unsigned
		byte[] appendZeroByte = ArrayUtils.addAll(new byte[1], privBytes);

		ECKey ecKey = new ECKey(new BigInteger(appendZeroByte));

		return ecKey;
	}

	public String decryptPK(String base58Priv) throws Exception {
		if (this.isDoubleEncrypted()) {

			if (this.temporySecondPassword == null)
				throw new Exception("You must provide a second password");

			base58Priv = decryptPK(base58Priv, getSharedKey(), this.temporySecondPassword, this.getPbkdf2Iterations());
		}

		return base58Priv;
	}

	public ECKey decodePK(String base58Priv) throws Exception {
		return decodeBase58PK(decryptPK(base58Priv));
	}

	public Map<String, String> getLabelMap() {
		Map<String, String> _labelMap = new HashMap<String, String>();

		List<Map<String, Object>> addressBook = this.getAddressBookMap();

		if (addressBook != null) {
			for (Map<String, Object> addr_book : addressBook) {
				_labelMap.put((String) addr_book.get("addr"),
						(String) addr_book.get("label"));
			}
		}

		if (this.getKeysMap() != null) {
			for (Map<String, Object> key_map : this.getKeysMap()) {
				String label = (String) key_map.get("label");

				if (label != null)
					_labelMap.put((String) key_map.get("addr"), label);
			}
		}

		return _labelMap;
	}

	public Map<String, Object> findAddressBookEntry(String address) {
		List<Map<String, Object>> addressBook = this.getAddressBookMap();

		if (addressBook != null) {
			for (Map<String, Object> addr_book : addressBook) {
				if (addr_book.get("addr").equals(address))
					return addr_book;
			}
		}

		return null;
	}

	public Map<String, Object> findKey(String address) {
		for (Map<String, Object> key : this.getKeysMap()) {
			String addr = (String) key.get("addr");

			if (addr.equals(address))
				return key;
		}
		return null;
	}

	public boolean isMine(String address) {
		for (Map<String, Object> key : this.getKeysMap()) {
			String addr = (String) key.get("addr");

			if (addr.equals(address))
				return true;
		}

		return false;
	}

	public void setTag(String address, long tag) {
		if (this.isMine(address)) {
			findKey(address).put("tag", tag);
		}
	}

	public void addLabel(String address, String label) {
		if (this.isMine(address)) {
			findKey(address).put("label", label);
		} else {
			Map<String, Object> entry = findAddressBookEntry(address);
			if (entry != null) {
				entry.put("label", label);
			} else {
				List<Map<String, Object>> addressBook = this
						.getAddressBookMap();

				if (addressBook == null) {
					addressBook = new ArrayList<Map<String, Object>>();
					root.put("address_book", addressBook);
				}

				HashMap<String, Object> map = new HashMap<String, Object>();
				map.put("addr", address);
				map.put("label", label);

				addressBook.add(map);
			}
		}

		EventListeners.invokeWalletDidChange();
	}

	
	protected void addKeysTobitoinJWallet(Wallet wallet, boolean enableTagFiler, int tagFilter) throws Exception {

		wallet.keychain.clear();

		for (Map<String, Object> key : this.getKeysMap()) {

			String base58Priv = (String) key.get("priv");
			String addr = (String) key.get("addr");

			if (base58Priv == null) {
				continue;
			}

			MyECKey encoded_key = new MyECKey(addr, base58Priv, this);

			if (key.get("label") != null)
				encoded_key.setLabel((String) key.get("label"));

			Long tag = 0L;
			if (key.get("tag") != null) {
				tag = (Long) key.get("tag");

				encoded_key.setTag((int) (long) tag);
			}

			try {
				if (!enableTagFiler || tag == tagFilter)
					wallet.addKey(encoded_key);
			} catch (IllegalArgumentException e) {
				e.printStackTrace();
			}
		}
	}

	public static class WalletOverride extends Wallet {
		public WalletOverride(NetworkParameters params) {
			super(params);
		}
	}
	
	public Wallet getBitcoinJWallet() throws Exception {
		// Construct a BitcoinJ wallet containing all our private keys
		Wallet keywallet = new WalletOverride(params);
		
		addKeysTobitoinJWallet(keywallet, true, 0);

		return keywallet;
	}


	public synchronized boolean removeKey(ECKey key) {
		
		final String address = key.toAddress(params).toString();

		final List<Map<String, Object>> keyMap = getKeysMap();
		
		for (int ii = 0; ii < keyMap.size(); ++ii) {
			Map<String, Object> map = keyMap.get(ii);
			
			if (map.get("addr").equals(address)) {
				keyMap.remove(ii);
				break;
			}
		}
		
		return true;
	}

	public boolean addWatchOnly(String address) {
		Map<String, Object> map = new HashMap<String, Object>();

		map.put("addr", address);

		getKeysMap().add(map);

		return true;
	}

	public boolean addKey(ECKey key, String label) throws Exception {
		Map<String, Object> map = new HashMap<String, Object>();

		String base58Priv = new String(Base58.encode(key.getPrivKeyBytes()));

		map.put("addr", key.toAddress(params).toString());

		if (label != null) {
			if (label.length() == 0 || label.length() > 255)
				throw new Exception("Label must be between 0 & 255 characters");

			map.put("label", label);
		}

		if (this.isDoubleEncrypted()) {
			if (temporySecondPassword == null)
				throw new Exception("You must provide a second password");

			map.put("priv", encryptPK(base58Priv, getSharedKey(), temporySecondPassword, this.getPbkdf2Iterations()));

		} else {
			map.put("priv", base58Priv);
		}

		getKeysMap().add(map);

		return true;
	}

	public boolean validateSecondPassword(String secondPassword) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");

			{
				// N Rounds of SHA256
				byte[] data = md.digest((getSharedKey() + secondPassword).getBytes("UTF-8"));

				for (int ii = 1; ii < this.getPbkdf2Iterations(); ++ii) {
					data = md.digest(data);
				}

				String dpasswordhash = new String(Hex.encode(data));
				if (dpasswordhash.equals(getDPasswordHash()))
					return true;
			}

		} catch (Exception e) {
			e.printStackTrace();
		}

		return false;
	}


	private static byte[] copyOfRange(byte[] source, int from, int to) {
		byte[] range = new byte[to - from];
		System.arraycopy(source, from, range, 0, range.length);

		return range;
	}

	// AES 256 PBKDF2 CBC iso10126 decryption
	// 16 byte IV must be prepended to ciphertext - Compatible with crypto-js
	public static String decrypt(String ciphertext, String password, final int PBKDF2Iterations) throws Exception {
		byte[] cipherdata = Base64.decode(ciphertext, Base64.NO_WRAP);

		//Sperate the IV and cipher data
		byte[] iv = copyOfRange(cipherdata, 0, AESBlockSize * 4);
		byte[] input = copyOfRange(cipherdata, AESBlockSize * 4, cipherdata.length);

		PBEParametersGenerator generator = new PKCS5S2ParametersGenerator();
		generator.init(PBEParametersGenerator.PKCS5PasswordToUTF8Bytes(password.toCharArray()), iv, PBKDF2Iterations);
		KeyParameter keyParam = (KeyParameter)generator.generateDerivedParameters(256);

		CipherParameters params = new ParametersWithIV(keyParam, iv);

		// setup AES cipher in CBC mode with PKCS7 padding
		BlockCipherPadding padding = new ISO10126d2Padding();
		BufferedBlockCipher cipher = new PaddedBufferedBlockCipher(new CBCBlockCipher(new AESEngine()), padding);
		cipher.reset();
		cipher.init(false, params);

		// create a temporary buffer to decode into (it'll include padding)
		byte[] buf = new byte[cipher.getOutputSize(input.length)];
		int len = cipher.processBytes(input, 0, input.length, buf, 0);
		len += cipher.doFinal(buf, len);

		// remove padding
		byte[] out = new byte[len];
		System.arraycopy(buf, 0, out, 0, len);

		// return string representation of decoded bytes
		return new String(out, "UTF-8"); 
	}


	private static byte[] cipherData(BufferedBlockCipher cipher, byte[] data)
			throws Exception
			{
		int minSize = cipher.getOutputSize(data.length);
		byte[] outBuf = new byte[minSize];
		int length1 = cipher.processBytes(data, 0, data.length, outBuf, 0);
		int length2 = cipher.doFinal(outBuf, length1);
		int actualLength = length1 + length2;
		byte[] result = new byte[actualLength];
		System.arraycopy(outBuf, 0, result, 0, result.length);
		return result;
			}

	// Encrypt compatible with crypto-js
	public static String encrypt(String text, String password, final int PBKDF2Iterations) throws Exception {

		if (password == null)
			throw new Exception("You must provide an ecryption password");

		// Use secure random to generate a 16 byte iv
		SecureRandom random = new SecureRandom();
		byte iv[] = new byte[AESBlockSize * 4];
		random.nextBytes(iv);

		byte[] textbytes = text.getBytes("UTF-8");

		PBEParametersGenerator generator = new PKCS5S2ParametersGenerator();
		generator.init(PBEParametersGenerator.PKCS5PasswordToUTF8Bytes(password.toCharArray()), iv, PBKDF2Iterations);
		KeyParameter keyParam = (KeyParameter)generator.generateDerivedParameters(256);

		CipherParameters params = new ParametersWithIV(keyParam, iv);

		// setup AES cipher in CBC mode with PKCS7 padding
		BlockCipherPadding padding = new ISO10126d2Padding();
		BufferedBlockCipher cipher = new PaddedBufferedBlockCipher(new CBCBlockCipher(new AESEngine()), padding);
		cipher.reset();
		cipher.init(true, params);

		byte[] outBuf = cipherData(cipher, textbytes);

		// Append to IV to the output
		byte[] ivAppended = ArrayUtils.addAll(iv, outBuf);

		return new String(Base64.encode(ivAppended, Base64.NO_WRAP), "UTF-8");
	}

	// Decrypt a double encrypted private key
	public static String decryptPK(String key, String sharedKey, String password, final int PBKDF2Iterations)
			throws Exception {
		return decrypt(key, sharedKey + password, PBKDF2Iterations);
	}

	// Decrypt a double encrypted private key
	public static String encryptPK(String key, String sharedKey, String password, final int PBKDF2Iterations)
			throws Exception {
		return encrypt(key, sharedKey + password, PBKDF2Iterations);
	}

	// Decrypt a Wallet file and parse the JSON
	@SuppressWarnings("unchecked")
	public static Map<String, Object> decryptPayload(String payload, String password) throws Exception {
		if (payload == null || payload.length() == 0 || password == null
				|| password.length() == 0)
			return null;

		String decrypted = decrypt(payload, password, DefaultPBKDF2Iterations);

		if (decrypted == null || decrypted.length() == 0)
			return null;

		JSONParser parser = new JSONParser();

		try {
			return (Map<String, Object>) parser.parse(decrypted);
		} catch (Exception e) {
			System.out.println(decrypted);

			throw e;
		}
	}

}
