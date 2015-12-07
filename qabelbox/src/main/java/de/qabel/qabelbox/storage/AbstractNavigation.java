package de.qabel.qabelbox.storage;

import android.support.annotation.Nullable;

import de.qabel.core.crypto.CryptoUtils;
import de.qabel.core.crypto.QblECKeyPair;
import de.qabel.qabelbox.exceptions.QblStorageException;
import de.qabel.qabelbox.exceptions.QblStorageNameConflict;
import de.qabel.qabelbox.exceptions.QblStorageNotFound;
import de.qabel.qabelbox.providers.BoxProvider;

import org.apache.commons.lang3.NotImplementedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.params.KeyParameter;

import java.io.*;
import java.security.InvalidKeyException;
import java.util.*;
import java.util.AbstractMap.SimpleEntry;

public abstract class AbstractNavigation implements BoxNavigation {

	private static final Logger logger = LoggerFactory.getLogger(AbstractNavigation.class.getName());
	protected byte[] dmKey;

	protected DirectoryMetadata dm;
	protected final QblECKeyPair keyPair;
	protected final byte[] deviceId;
	protected TransferManager transferManager;
	protected final CryptoUtils cryptoUtils;

	private final Set<String> deleteQueue = new HashSet<>();
	private final Set<FileUpdate> updatedFiles = new HashSet<>();

	protected String path;


	public AbstractNavigation(DirectoryMetadata dm, QblECKeyPair keyPair, byte[] dmKey, byte[] deviceId,
							  TransferManager transferManager, String path) {
		this.dm = dm;
		this.keyPair = keyPair;
		this.deviceId = deviceId;
		this.transferManager = transferManager;
		this.path = path;
        this.dmKey = dmKey;
		cryptoUtils = new CryptoUtils();
	}

	public String getPath() {
		return path;
	}

	public String getPath(BoxObject object) {
		if (object instanceof BoxFolder) {
			return path + object.name + BoxProvider.PATH_SEP;
		} else {
			return path + object.name;
		}
	}

	protected File blockingDownload(String name, TransferManager.BoxTransferListener boxTransferListener) throws QblStorageNotFound {
		File file = transferManager.createTempFile();
		int id = transferManager.download(name, file, boxTransferListener);
		if (transferManager.waitFor(id)) {
			return file;
		} else {
			throw new QblStorageNotFound("File not found");
		}
	}

	protected Long blockingUpload(String name,
								  File file, @Nullable TransferManager.BoxTransferListener boxTransferListener) {
		int id = transferManager.upload(name, file, boxTransferListener);
		transferManager.waitFor(id);
		return currentSecondsFromEpoch();
	}

	private static long currentSecondsFromEpoch() {
		return System.currentTimeMillis() / 1000;
	}

	/**
     * Navigates to a direct subfolder.
     * @param target Subfolder to navigate to
     * @throws QblStorageException
     */
	@Override
	public void navigate(BoxFolder target) throws QblStorageException {
        boolean isSubfolder = false;
        for (BoxFolder boxFolder : listFolders()) {
            if (boxFolder.ref.equals(target.ref)) {
                isSubfolder = true;
                break;
            }
        }
        if (!isSubfolder) {
            throw new QblStorageNotFound(target.name + " is not a direct subfolder of " + path);
        }
		try {
			File indexDl = blockingDownload(target.ref, null);
			File tmp = File.createTempFile("dir", "db", dm.getTempDir());
			KeyParameter keyParameter = new KeyParameter(target.key);
			if (cryptoUtils.decryptFileAuthenticatedSymmetricAndValidateTag(
					new FileInputStream(indexDl), tmp, keyParameter)) {
				dm = DirectoryMetadata.openDatabase(
						tmp, deviceId, target.ref, this.dm.getTempDir());
				path = path + target.name + BoxProvider.PATH_SEP;
				dmKey = target.key;
			} else {
				throw new QblStorageNotFound("Invalid key");
			}
		} catch (IOException | InvalidKeyException e) {
			throw new QblStorageException(e);
		}
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

	protected abstract DirectoryMetadata reloadMetadata() throws QblStorageException;

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
		return dm.listExternals();
	}

	@Override
	public BoxFile upload(String name, InputStream content,
						  @Nullable TransferManager.BoxTransferListener boxTransferListener) throws QblStorageException {
		KeyParameter key = cryptoUtils.generateSymmetricKey();
		String block = UUID.randomUUID().toString();
		BoxFile boxFile = new BoxFile(block, name, null, 0L, key.getKey());
		SimpleEntry<Long, Long> mtimeAndSize = uploadEncrypted(content, key, "blocks/" + block, boxTransferListener);
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

	protected SimpleEntry<Long, Long> uploadEncrypted(
			InputStream content, KeyParameter key, String block,
			@Nullable TransferManager.BoxTransferListener boxTransferListener) throws QblStorageException {
		try {
			File tempFile = File.createTempFile("upload", "up", dm.getTempDir());
			OutputStream outputStream = new FileOutputStream(tempFile);
			if (!cryptoUtils.encryptStreamAuthenticatedSymmetric(content, outputStream, key, null)) {
				throw new QblStorageException("Encryption failed");
			}
			outputStream.flush();
			Long size = tempFile.length();
			Long mtime = blockingUpload(block, tempFile, boxTransferListener);
			return new SimpleEntry<>(mtime, size);
		} catch (IOException | InvalidKeyException e) {
			throw new QblStorageException(e);
		}
	}

	@Override
	public InputStream download(BoxFile boxFile,
								@Nullable TransferManager.BoxTransferListener boxTransferListener) throws QblStorageException {
		File download = blockingDownload("blocks/" + boxFile.block, boxTransferListener);
		KeyParameter key = new KeyParameter(boxFile.key);
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
		KeyParameter secretKey = cryptoUtils.generateSymmetricKey();
		BoxFolder folder = new BoxFolder(dm.getFileName(), name, secretKey.getKey());
		this.dm.insertFolder(folder);
		BoxNavigation newFolder = new FolderNavigation(dm, keyPair, secretKey.getKey(),
			deviceId, transferManager, path + BoxProvider.PATH_SEP + folder.name);
		newFolder.commit();
		return folder;
	}

	@Override
	public void delete(BoxObject boxObject) throws QblStorageException {
		if (boxObject instanceof BoxFile) {
			delete((BoxFile) boxObject);
		} else if (boxObject instanceof BoxFolder) {
			delete((BoxFolder) boxObject);
		} else if (boxObject instanceof BoxExternal) {
			delete((BoxExternal) boxObject);
		}
	}

	@Override
	public void delete(BoxFile file) throws QblStorageException {
		dm.deleteFile(file);
		deleteQueue.add("blocks/" + file.block);
	}

	@Override
	public void delete(BoxFolder folder) throws QblStorageException {
        //TODO: Store current folder to navigate back to originating dir without the new deleteNavigate
		BoxNavigation deleteNavigate = new FolderNavigation(dm, keyPair, dmKey, deviceId, transferManager, path);
        deleteNavigate.navigate(folder);
		for (BoxFile file: deleteNavigate.listFiles()) {
			logger.info("Deleting file " + file.name);
            deleteNavigate.delete(file);
		}
		for (BoxFolder subFolder: deleteNavigate.listFolders()) {
			logger.info("Deleting folder " + folder.name);
            deleteNavigate.delete(subFolder);
		}
        deleteNavigate.commit();
		dm.deleteFolder(folder);
		deleteQueue.add(folder.ref);
	}

	@Override
	public void delete(BoxExternal external) throws QblStorageException {
		throw new NotImplementedException("Externals are not yet implemented!");
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
