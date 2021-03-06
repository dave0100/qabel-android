package de.qabel.qabelbox.storage;

import android.content.Context;
import android.support.annotation.Nullable;

import com.amazonaws.util.IOUtils;

import de.qabel.core.crypto.DecryptedPlaintext;
import de.qabel.core.crypto.QblECKeyPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.InvalidCipherTextException;
import org.spongycastle.crypto.params.KeyParameter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.InvalidKeyException;
import java.util.Stack;

import de.qabel.qabelbox.exceptions.QblStorageException;
import de.qabel.qabelbox.exceptions.QblStorageNotFound;

public class FolderNavigation extends AbstractNavigation {

	private static final Logger logger = LoggerFactory.getLogger(FolderNavigation.class.getName());

	public FolderNavigation(DirectoryMetadata dm, QblECKeyPair keyPair, @Nullable byte[] dmKey, byte[] deviceId,
	                        TransferManager transferUtility, BoxVolume boxVolume, String path,
							@Nullable Stack<BoxFolder> parents, Context context) {
		super(dm, keyPair, dmKey, deviceId, transferUtility, boxVolume, path, parents, context);
	}

	@Override
	protected void uploadDirectoryMetadata() throws QblStorageException {
		if (currentPath.equals("/")) {
			uploadDirectoryMetadataRoot();
		}
		else {
			uploadDirectoryMetadataSubFolder();
		}
	}

	private void uploadDirectoryMetadataRoot() throws QblStorageException {
		try {
			byte[] plaintext = IOUtils.toByteArray(new FileInputStream(dm.path));
			byte[] encrypted = cryptoUtils.createBox(keyPair, keyPair.getPub(), plaintext, 0);
			File tmp = transferManager.createTempFile();
			FileOutputStream fileOutputStream = new FileOutputStream(tmp);
			fileOutputStream.write(encrypted);
			fileOutputStream.close();
			blockingUpload(dm.getFileName(), tmp, null);
		} catch (IOException | InvalidKeyException e) {
			throw new QblStorageException(e);
		}
	}

	private void uploadDirectoryMetadataSubFolder() throws QblStorageException {
		logger.info("Uploading directory metadata");
		try {
			uploadEncrypted(new FileInputStream(dm.getPath()), new KeyParameter(dmKey),
					dm.getFileName(), null);
		} catch (FileNotFoundException e) {
			throw new QblStorageException(e);
		}
	}

	protected DirectoryMetadata reloadMetadata() throws QblStorageException {
		if (currentPath.equals("/")) {
			return reloadMetadataRoot();
		}
		else {
			return reloadMetadataSubFolder();
		}
	}

	protected DirectoryMetadata reloadMetadataSubFolder() throws QblStorageException {
		logger.info("Reloading directory metadata");
		// duplicate of navigate()
		try {
			File indexDl = blockingDownload(dm.getFileName(), null);
			File tmp = File.createTempFile("dir", "db", dm.getTempDir());
			if (cryptoUtils.decryptFileAuthenticatedSymmetricAndValidateTag(
					new FileInputStream(indexDl), tmp, new KeyParameter(dmKey))) {
				return DirectoryMetadata.openDatabase(tmp, deviceId, dm.getFileName(), dm.getTempDir());
			} else {
				throw new QblStorageNotFound("Invalid key");
			}
		} catch (IOException | InvalidKeyException e) {
			throw new QblStorageException(e);
		}
	}

	protected DirectoryMetadata reloadMetadataRoot() throws QblStorageException {
		// TODO: duplicate with BoxVoume.navigate()
		String rootRef = dm.getFileName();
		File indexDl = blockingDownload(rootRef, null);
		File tmp;
		try {
			byte[] encrypted = IOUtils.toByteArray(new FileInputStream(indexDl));
			DecryptedPlaintext plaintext = cryptoUtils.readBox(keyPair, encrypted);
			tmp = File.createTempFile("dir", "db", dm.getTempDir());
			logger.info("Using " + tmp.toString() + " for the metadata file");
			OutputStream out = new FileOutputStream(tmp);
			out.write(plaintext.getPlaintext());
			out.close();
		} catch (IOException | InvalidCipherTextException | InvalidKeyException e) {
			throw new QblStorageException(e);
		}
		return DirectoryMetadata.openDatabase(tmp, deviceId, rootRef, dm.getTempDir());
	}

	@Override
	public void reload() throws QblStorageException {
		dm = reloadMetadata();
	}
}
