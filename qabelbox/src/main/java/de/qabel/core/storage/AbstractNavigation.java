package de.qabel.core.storage;

import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility;

import de.qabel.core.crypto.CryptoUtils;
import de.qabel.core.crypto.QblECKeyPair;
import de.qabel.core.exceptions.QblStorageException;
import de.qabel.core.exceptions.QblStorageNameConflict;
import de.qabel.core.exceptions.QblStorageNotFound;
import org.apache.commons.lang3.NotImplementedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.security.InvalidKeyException;
import java.util.*;
import java.util.AbstractMap.SimpleEntry;

public abstract class AbstractNavigation implements BoxNavigation {

	private static final Logger logger = LoggerFactory.getLogger(AbstractNavigation.class.getName());

	DirectoryMetadata dm;
	final QblECKeyPair keyPair;
	final byte[] deviceId;
	protected TransferManager transferManager;
	final CryptoUtils cryptoUtils;

	private final Set<String> deleteQueue = new HashSet<>();
	private final Set<FileUpdate> updatedFiles = new HashSet<>();



	public AbstractNavigation(DirectoryMetadata dm, QblECKeyPair keyPair, byte[] deviceId,
							  TransferManager transferManager) {
		this.dm = dm;
		this.keyPair = keyPair;
		this.deviceId = deviceId;
		this.transferManager = transferManager;
		cryptoUtils = new CryptoUtils();
	}

	protected File blockingDownload(String name) throws QblStorageNotFound {
		File file = transferManager.createTempFile();
		int id = transferManager.download(name, file);
		if (transferManager.waitFor(id)) {
			return file;
		} else {
			throw new QblStorageNotFound("File not found");
		}
	}

	protected Long blockingUpload(String name,
								  File file) {
		int id = transferManager.upload(name, file);
		transferManager.waitFor(id);
		return file.length();
	}

	@Override
	public BoxNavigation navigate(BoxFolder target) throws QblStorageException {
		try {
			File indexDl = blockingDownload(target.ref);
			File tmp = File.createTempFile("dir", "db", dm.getTempDir());
			SecretKey key = makeKey(target.key);
			if (cryptoUtils.decryptFileAuthenticatedSymmetricAndValidateTag(
					new FileInputStream(indexDl), tmp, key)) {
				DirectoryMetadata dm = DirectoryMetadata.openDatabase(
						tmp, deviceId, target.ref, this.dm.getTempDir());
				return new FolderNavigation(dm, keyPair, target.key, deviceId, transferManager);
			} else {
				throw new QblStorageNotFound("Invalid key");
			}
		} catch (IOException | InvalidKeyException e) {
			throw new QblStorageException(e);
		}
	}

	protected abstract DirectoryMetadata reloadMetadata() throws QblStorageException;

	protected SecretKey makeKey(byte[] key2) {
		return new SecretKeySpec(key2, "AES");
	}

	@Override
	public void commit() throws QblStorageException {
		byte[] version = dm.getVersion();
		dm.commit();
		DirectoryMetadata updatedDM = null;
		try {
			updatedDM = reloadMetadata();
		} catch (QblStorageNotFound e) {
			logger.info("Could not reload metadata");
		}
		// the remote version has changed from the _old_ version
		if ((updatedDM != null) && (!Arrays.equals(version, updatedDM.getVersion()))) {
			logger.info("Conflicting version");
			// ignore our local directory metadata
			// all changes that are not inserted in the new dm are _lost_!
			dm = updatedDM;
			for (FileUpdate update: updatedFiles) {
				handleConflict(update);
			}
			dm.commit();
		}
		uploadDirectoryMetadata();
		for (String ref: deleteQueue) {
			blockingDelete(ref);
		}
		// TODO: make a test fail without these
		deleteQueue.clear();
		updatedFiles.clear();
	}

	protected void blockingDelete(String ref) {
		transferManager.delete(ref);

	}

	private void handleConflict(FileUpdate update) throws QblStorageException {
		BoxFile local = update.updated;
		BoxFile newFile = dm.getFile(local.name);
		if (newFile == null) {
			try {
				dm.insertFile(local);
			} catch (QblStorageNameConflict e) {
				// name clash with a folder or external
				local.name = conflictName(local);
				// try again until we get no name clash
				handleConflict(update);
			}
		} else if (newFile.equals(update.old)) {
			logger.info("No conflict for the file " + local.name);
		} else {
			logger.info("Inserting conflict marked file");
			local.name = conflictName(local);
			if (update.old != null) {
				dm.deleteFile(update.old);
			}
			if (dm.getFile(local.name) == null) {
				dm.insertFile(local);
			}
		}
	}

	private String conflictName(BoxFile local) {
		return local.name + "_conflict_" + local.mtime.toString();
	}

	protected abstract void uploadDirectoryMetadata() throws QblStorageException;

	@Override
	public BoxNavigation navigate(BoxExternal target) {
		throw new NotImplementedException("Externals are not yet implemented!");
	}

	@Override
	public List<BoxFile> listFiles() throws QblStorageException {
		return dm.listFiles();
	}

	@Override
	public List<BoxFolder> listFolders() throws QblStorageException {
		return dm.listFolders();
	}

	@Override
	public List<BoxExternal> listExternals() throws QblStorageException {
		//return dm.listExternals();
		throw new NotImplementedException("Externals are not yet implemented!");
	}

	@Override
	public BoxFile upload(String name, InputStream content) throws QblStorageException {
		SecretKey key = cryptoUtils.generateSymmetricKey();
		String block = UUID.randomUUID().toString();
		BoxFile boxFile = new BoxFile(block, name, null, 0L, key.getEncoded());
		SimpleEntry<Long, Long> mtimeAndSize = uploadEncrypted(content, key, "blocks/" + block);
		boxFile.mtime = mtimeAndSize.getKey();
		boxFile.size = mtimeAndSize.getValue();
		// Overwrite = delete old file, upload new file
		BoxFile oldFile = dm.getFile(name);
		if (oldFile != null) {
			deleteQueue.add(oldFile.block);
			dm.deleteFile(oldFile);
		}
		updatedFiles.add(new FileUpdate(oldFile, boxFile));
		dm.insertFile(boxFile);
		return boxFile;
	}

	protected SimpleEntry<Long, Long> uploadEncrypted(InputStream content, SecretKey key, String block) throws QblStorageException {
		try {
			File tempFile = File.createTempFile("upload", "up", dm.getTempDir());
			OutputStream outputStream = new FileOutputStream(tempFile);
			if (!cryptoUtils.encryptStreamAuthenticatedSymmetric(content, outputStream, key, null)) {
				throw new QblStorageException("Encryption failed");
			}
			outputStream.flush();
			Long size = tempFile.length();
			Long mtime = blockingUpload(block, tempFile);
			return new SimpleEntry<>(mtime, size);
		} catch (IOException | InvalidKeyException e) {
			throw new QblStorageException(e);
		}
	}

	@Override
	public InputStream download(BoxFile boxFile) throws QblStorageException {
		File download = blockingDownload("blocks/" + boxFile.block);
		SecretKey key = makeKey(boxFile.key);
		try {
			File temp = File.createTempFile("upload", "down", dm.getTempDir());
			if (!cryptoUtils.decryptFileAuthenticatedSymmetricAndValidateTag(
					new FileInputStream(download), temp, key)) {
				throw new QblStorageException("Decryption failed");
			}
			return new FileInputStream(temp);
		} catch (IOException | InvalidKeyException e) {
			throw new QblStorageException(e);
		}
	}

	@Override
	public BoxFolder createFolder(String name) throws QblStorageException {
		DirectoryMetadata dm = DirectoryMetadata.newDatabase(null, deviceId, this.dm.getTempDir());
		SecretKey secretKey = cryptoUtils.generateSymmetricKey();
		BoxFolder folder = new BoxFolder(dm.getFileName(), name, secretKey.getEncoded());
		this.dm.insertFolder(folder);
		BoxNavigation newFolder = new FolderNavigation(dm, keyPair, secretKey.getEncoded(),
				deviceId, transferManager);
		newFolder.commit();
		return folder;
	}

	@Override
	public void delete(BoxFile file) throws QblStorageException {
		dm.deleteFile(file);
		deleteQueue.add("blocks/" + file.block);
	}

	@Override
	public void delete(BoxFolder folder) throws QblStorageException {
		BoxNavigation folderNav = navigate(folder);
		for (BoxFile file: folderNav.listFiles()) {
			logger.info("Deleting file " + file.name);
			folderNav.delete(file);
		}
		for (BoxFolder subFolder: folderNav.listFolders()) {
			logger.info("Deleting folder " + folder.name);
			folderNav.delete(subFolder);
		}
		folderNav.commit();
		dm.deleteFolder(folder);
		deleteQueue.add(folder.ref);
	}

	@Override
	public void delete(BoxExternal external) throws QblStorageException {

	}

	@Override
	public BoxFile rename(BoxFile file, String name) throws QblStorageException {
		dm.deleteFile(file);
		file.name = name;
		dm.insertFile(file);
		return file;
	}

	@Override
	public BoxFolder rename(BoxFolder folder, String name) throws QblStorageException {
		dm.deleteFolder(folder);
		folder.name = name;
		dm.insertFolder(folder);
		return folder;
	}

	@Override
	public BoxExternal rename(BoxExternal external, String name) throws QblStorageException {
		dm.deleteExternal(external);
		external.name = name;
		dm.insertExternal(external);
		return external;
	}

	private static class FileUpdate {
		final BoxFile old;
		final BoxFile updated;

		public FileUpdate(BoxFile old, BoxFile updated) {
			this.old = old;
			this.updated = updated;
		}

		@Override
		public int hashCode() {
			int result = old != null ? old.hashCode() : 0;
			result = 31 * result + (updated != null ? updated.hashCode() : 0);
			return result;
		}
	}
}
