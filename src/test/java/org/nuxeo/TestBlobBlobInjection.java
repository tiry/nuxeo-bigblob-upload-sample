/*
 * (C) Copyright 2006-2014 Nuxeo SAS (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Thierry Delprat
 */

package org.nuxeo;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import junit.framework.Assert;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.common.utils.FileUtils;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.impl.blob.FileBlob;
import org.nuxeo.ecm.core.api.repository.RepositoryManager;
import org.nuxeo.ecm.core.storage.StorageBlob;
import org.nuxeo.ecm.core.storage.binary.AbstractBinaryManager;
import org.nuxeo.ecm.core.storage.binary.Binary;
import org.nuxeo.ecm.core.storage.binary.BinaryManager;
import org.nuxeo.ecm.core.storage.binary.BinaryManagerRootDescriptor;
import org.nuxeo.ecm.core.storage.binary.BinaryManagerService;
import org.nuxeo.ecm.core.storage.binary.LocalBinaryManager;
import org.nuxeo.ecm.core.test.CoreFeature;
import org.nuxeo.ecm.core.test.TransactionalFeature;
import org.nuxeo.ecm.core.test.annotations.Granularity;
import org.nuxeo.ecm.core.test.annotations.RepositoryConfig;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;

import com.google.inject.Inject;

/**
 * 
 * @author tiry
 *
 */
@RunWith(FeaturesRunner.class)
@Features({ TransactionalFeature.class, CoreFeature.class })
@RepositoryConfig(cleanup = Granularity.METHOD, init = SimpleRepoInit.class)
public class TestBlobBlobInjection {

    public static final int MIN_BUF_SIZE = 8 * 1024; // 8 kB

    public static final int MAX_BUF_SIZE = 1024 * 1024; // 1024 kB

    @Inject
    CoreSession session;

    /**
     * The BinaryManager configuration is not directly accessible, so we use a
     * little bit of reflection to get the info we need.
     * 
     * @param bm
     * @return
     * @throws Exception
     */
    protected BinaryManagerRootDescriptor getBinaryManagerDescriptor(
            AbstractBinaryManager bm) throws Exception {

        // Field descriptorField = bm.getClass().getField("descriptor");

        // AbstractBinaryManager.class.getDeclaredFields();
        for (Field field : AbstractBinaryManager.class.getDeclaredFields()) {
            if (field.getName().equals("descriptor")) {

                field.setAccessible(true);
                BinaryManagerRootDescriptor desc = (BinaryManagerRootDescriptor) field.get(bm);
                return desc;
            }
        }
        return null;

    }

    /**
     * Java implementation for computing digest
     * 
     * @param source
     * @param digestName
     * @return
     * @throws Exception
     */
    protected String computeDigest(Blob source, String digestName)
            throws Exception {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(digestName);
        } catch (NoSuchAlgorithmException e) {
            throw (IOException) new IOException().initCause(e);
        }

        InputStream in = source.getStream();

        int size = in.available();
        if (size == 0) {
            size = MAX_BUF_SIZE;
        } else if (size < MIN_BUF_SIZE) {
            size = MIN_BUF_SIZE;
        } else if (size > MAX_BUF_SIZE) {
            size = MAX_BUF_SIZE;
        }
        byte[] buf = new byte[size];
        int n;
        while ((n = in.read(buf)) != -1) {
            digest.update(buf, 0, n);
        }
        in.close();
        return AbstractBinaryManager.toHexString(digest.digest());
    }

    /**
     * Here we inject the stream directly on the filesystem of the target
     * BinaryManager.
     * 
     * This code shows how to get the target configuration, and even if here the
     * actual copy is done in java, it could be done via system commands.
     * 
     * @param blob
     * @return
     * @throws Exception
     */
    protected Binary injectBlobAtFileSystemLevel(Blob blob) throws Exception {
        RepositoryManager rm = Framework.getLocalService(RepositoryManager.class);
        BinaryManagerService bms = Framework.getLocalService(BinaryManagerService.class);
        BinaryManager bm = bms.getBinaryManager(rm.getDefaultRepositoryName());

        Assert.assertTrue(bm instanceof LocalBinaryManager);

        LocalBinaryManager lbm = (LocalBinaryManager) bm;

        // get the FileSystem location
        File binaryStore = lbm.getStorageDir();

        // get the digest config
        BinaryManagerRootDescriptor desc = getBinaryManagerDescriptor(lbm);

        // compute the digest
        String digest = computeDigest(blob, desc.digest);
        
        // create the target file (with folder sub tree)
        StringBuilder buf = new StringBuilder(3 * desc.depth - 1);
        for (int i = 0; i < desc.depth; i++) {
            if (i != 0) {
                buf.append(File.separatorChar);
            }
            buf.append(digest.substring(2 * i, 2 * i + 2));
        }
        File dir = new File(binaryStore, buf.toString());
        dir.mkdirs();        
        File target =  new File(dir, digest);
        
        // write content
        FileUtils.copyToFile(blob.getStream(), target);

        // now let's create the binary        
        return new Binary(target, digest);
    }

    /**
     * Here we inject the stream via the BinaryManager.
     * 
     * Compared to the "standard approach", using this approach allows to do the
     * upload outside of the transaction boundaries
     * 
     * @param blob
     * @return
     * @throws Exception
     */
    protected Binary injectBlobViaBinaryManager(Blob blob) throws Exception {
        RepositoryManager rm = Framework.getLocalService(RepositoryManager.class);
        BinaryManagerService bms = Framework.getLocalService(BinaryManagerService.class);
        BinaryManager bm = bms.getBinaryManager(rm.getDefaultRepositoryName());
        return bm.getBinary(blob.getStream());
    }

    /**
     * Get a File from the ClassLoader
     * 
     * @param name
     * @return
     */
    protected File getSourceFile(String name) {
        File source = FileUtils.getResourceFileFromContext(name);
        Assert.assertNotNull(source);
        Assert.assertTrue(source.exists());
        return source;
    }

    /**
     * Simply create a DocumentModel and attach the provided Blob to it. There
     * is nothing specific in this code : this trick is in the implementation
     * Class of the Blob
     * 
     * @param name
     * @param blob
     * @return
     * @throws Exception
     */
    protected DocumentRef createFileDocumentAndStoreBlob(String name, Blob blob)
            throws Exception {
        DocumentModel doc = session.createDocumentModel("/MyFolder", name,
                "File");
        doc.setProperty("dublincore", "title", "My File");
        doc.setPropertyValue("file:content", (Serializable) blob);
        doc = session.createDocument(doc);
        return doc.getRef();
    }

    @Test
    public void shouldPreInjectBlobContentViaBM() throws Exception {

        String sourceFileName = "sampleblob1.txt";
        String targetDocumentName = "sampleDoc1";

        // get the source File
        File source = getSourceFile(sourceFileName);

        // generate the Binary : here we use directly the BinaryManager
        Binary binary = injectBlobViaBinaryManager(new FileBlob(source));
        Assert.assertNotNull(binary);

        // wrap the binary in a Blob
        StorageBlob storageBlob = new StorageBlob(binary, sourceFileName,
                "text/plain", "UTF-8", binary.getDigest(), binary.getLength());

        // create Document and attach Blob to it
        DocumentRef docRef = createFileDocumentAndStoreBlob(targetDocumentName,
                storageBlob);

        // reload the document to be sure
        DocumentModel doc = session.getDocument(docRef);

        // check the Blob
        Blob blob = (Blob) doc.getPropertyValue("file:content");
        Assert.assertNotNull(blob);
        Assert.assertEquals(FileUtils.readFile(source), blob.getString());

    }

    @Test
    public void shouldPreInjectBlobContentViaFileSystem() throws Exception {

        String sourceFileName = "sampleblob2.txt";
        String targetDocumentName = "sampleDoc2";

        // get the source File
        File source = getSourceFile(sourceFileName);

        // generate the Binary : here we use directly the BinaryManager
        Binary binary = injectBlobAtFileSystemLevel(new FileBlob(source));
        Assert.assertNotNull(binary);

        // wrap the binary in a Blob
        StorageBlob storageBlob = new StorageBlob(binary, sourceFileName,
                "text/plain", "UTF-8", binary.getDigest(), binary.getLength());

        // create Document and attach Blob to it
        DocumentRef docRef = createFileDocumentAndStoreBlob(targetDocumentName,
                storageBlob);

        // reload the document to be sure
        DocumentModel doc = session.getDocument(docRef);

        // check the Blob
        Blob blob = (Blob) doc.getPropertyValue("file:content");
        Assert.assertNotNull(blob);
        Assert.assertEquals(FileUtils.readFile(source), blob.getString());

    }

}
