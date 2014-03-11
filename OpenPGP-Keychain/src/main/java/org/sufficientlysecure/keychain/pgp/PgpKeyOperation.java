/*
 * Copyright (C) 2012-2013 Dominik Schürmann <dominik@dominikschuermann.de>
 * Copyright (C) 2010-2014 Thialfihar <thi@thialfihar.org>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.thialfihar.android.apg.pgp;

import android.content.Context;
import android.util.Pair;

import org.spongycastle.bcpg.CompressionAlgorithmTags;
import org.spongycastle.bcpg.HashAlgorithmTags;
import org.spongycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.spongycastle.bcpg.sig.KeyFlags;
import org.spongycastle.jce.spec.ElGamalParameterSpec;
import org.spongycastle.openpgp.PGPEncryptedData;
import org.spongycastle.openpgp.PGPException;
import org.spongycastle.openpgp.PGPKeyPair;
import org.spongycastle.openpgp.PGPKeyRingGenerator;
import org.spongycastle.openpgp.PGPPrivateKey;
import org.spongycastle.openpgp.PGPPublicKey;
import org.spongycastle.openpgp.PGPPublicKeyRing;
import org.spongycastle.openpgp.PGPSecretKey;
import org.spongycastle.openpgp.PGPSecretKeyRing;
import org.spongycastle.openpgp.PGPSignature;
import org.spongycastle.openpgp.PGPSignatureGenerator;
import org.spongycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.spongycastle.openpgp.PGPSignatureSubpacketVector;
import org.spongycastle.openpgp.PGPUtil;
import org.spongycastle.openpgp.operator.PBESecretKeyDecryptor;
import org.spongycastle.openpgp.operator.PBESecretKeyEncryptor;
import org.spongycastle.openpgp.operator.PGPContentSignerBuilder;
import org.spongycastle.openpgp.operator.PGPDigestCalculator;
import org.spongycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.spongycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.spongycastle.openpgp.operator.jcajce.JcaPGPKeyPair;
import org.spongycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.spongycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder;

import org.thialfihar.android.apg.Constants;
import org.thialfihar.android.apg.Id;
import org.thialfihar.android.apg.R;
import org.thialfihar.android.apg.pgp.exception.PgpGeneralException;
import org.thialfihar.android.apg.provider.ProviderHelper;
import org.thialfihar.android.apg.util.IterableIterator;
import org.thialfihar.android.apg.util.Log;
import org.thialfihar.android.apg.util.Primes;

import java.io.IOException;
import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.List;
import java.util.TimeZone;

public class PgpKeyOperation {
    private final Context mContext;
    private final ProgressDialogUpdater mProgress;

    private static final int[] PREFERRED_SYMMETRIC_ALGORITHMS = new int[] {
            SymmetricKeyAlgorithmTags.AES_256, SymmetricKeyAlgorithmTags.AES_192,
            SymmetricKeyAlgorithmTags.AES_128, SymmetricKeyAlgorithmTags.CAST5,
            SymmetricKeyAlgorithmTags.TRIPLE_DES };
    private static final int[] PREFERRED_HASH_ALGORITHMS = new int[] { HashAlgorithmTags.SHA1,
            HashAlgorithmTags.SHA256, HashAlgorithmTags.RIPEMD160 };
    private static final int[] PREFERRED_COMPRESSION_ALGORITHMS = new int[] {
            CompressionAlgorithmTags.ZLIB, CompressionAlgorithmTags.BZIP2,
            CompressionAlgorithmTags.ZIP };

    public PgpKeyOperation(Context context, Progressable progress) {
        super();
        this.mContext = context;
        this.mProgress = progress;
    }

    void updateProgress(int message, int current, int total) {
        if (mProgress != null) {
            mProgress.setProgress(message, current, total);
        }
    }

    void updateProgress(int current, int total) {
        if (mProgress != null) {
            mProgress.setProgress(current, total);
        }
    }

    // TODO: key flags?
    public Key createKey(int algorithmChoice, int keySize, String passphrase,
       boolean isMasterKey) throws NoSuchAlgorithmException, PGPException, NoSuchProviderException,
       PgpGeneralException, InvalidAlgorithmParameterException {

        if (keySize < 512) {
            throw new PgpGeneralException(mContext.getString(R.string.error_key_size_minimum512bit));
        }

        if (passphrase == null) {
            passphrase = "";
        }

        int algorithm;
        KeyPairGenerator keyGen;

        switch (algorithmChoice) {
        case Id.choice.algorithm.dsa: {
            keyGen = KeyPairGenerator.getInstance("DSA", Constants.BOUNCY_CASTLE_PROVIDER_NAME);
            keyGen.initialize(keySize, new SecureRandom());
            algorithm = PGPPublicKey.DSA;
            break;
        }

        case Id.choice.algorithm.elgamal: {
            if (isMasterKey) {
                throw new PgpGeneralException(
                        mContext.getString(R.string.error_master_key_must_not_be_el_gamal));
            }
            keyGen = KeyPairGenerator.getInstance("ElGamal", Constants.BOUNCY_CASTLE_PROVIDER_NAME);
            BigInteger p = Primes.getBestPrime(keySize);
            BigInteger g = new BigInteger("2");

            ElGamalParameterSpec elParams = new ElGamalParameterSpec(p, g);

            keyGen.initialize(elParams);
            algorithm = PGPPublicKey.ELGAMAL_ENCRYPT;
            break;
        }

        case Id.choice.algorithm.rsa: {
            keyGen = KeyPairGenerator.getInstance("RSA", Constants.BOUNCY_CASTLE_PROVIDER_NAME);
            keyGen.initialize(keySize, new SecureRandom());

            algorithm = PGPPublicKey.RSA_GENERAL;
            break;
        }

        default: {
            throw new PgpGeneralException(
                    mContext.getString(R.string.error_unknown_algorithm_choice));
        }
        }

        // build new key pair
        PGPKeyPair keyPair = new JcaPGPKeyPair(algorithm, keyGen.generateKeyPair(), new Date());

        // define hashing and signing algos
        PGPDigestCalculator sha1Calc = new JcaPGPDigestCalculatorProviderBuilder().build().get(
                HashAlgorithmTags.SHA1);

        // Build key encrypter and decrypter based on passphrase
        PBESecretKeyEncryptor keyEncryptor = new JcePBESecretKeyEncryptorBuilder(
                PGPEncryptedData.CAST5, sha1Calc)
                .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME).build(passphrase.toCharArray());

        PGPPublicKey pubKey = keyPair.getPublicKey();
        if (!isMasterKey) {
            // since the keys are now serialized without sending them through a KeyRing, this
            // public key will be identified as a master key, as it has subSigs == null set,
            // give it an empty list of sub sigs to fix that
            pubKey = new PGPPublicKey(pubKey, null, new ArrayList());
        }
        PGPSecretKey secKey = new PGPSecretKey(keyPair.getPrivateKey(), pubKey,
            sha1Calc, isMasterKey, keyEncryptor);

        return new Key(secKey);
    }

    public void changeSecretKeyPassphrase(PGPSecretKeyRing keyRing, String oldPassphrase,
            String newPassphrase) throws IOException, PGPException, NoSuchProviderException {
        updateProgress(R.string.progress_building_key, 0, 100);
        if (oldPassphrase == null) {
            oldPassphrase = "";
        }
        if (newPassphrase == null) {
            newPassphrase = "";
        }

        PGPSecretKeyRing newKeyRing = PGPSecretKeyRing.copyWithNewPassword(
                keyRing,
                new JcePBESecretKeyDecryptorBuilder(new JcaPGPDigestCalculatorProviderBuilder()
                        .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME).build()).setProvider(
                        Constants.BOUNCY_CASTLE_PROVIDER_NAME).build(oldPassphrase.toCharArray()),
                new JcePBESecretKeyEncryptorBuilder(keyRing.getSecretKey()
                        .getKeyEncryptionAlgorithm()).build(newPassphrase.toCharArray()));

        updateProgress(R.string.progress_saving_key_ring, 50, 100);

        ProviderHelper.saveKeyRing(mContext, newKeyRing);

        updateProgress(R.string.progress_done, 100, 100);

    }

    private void buildNewSecretKey(ArrayList<String> userIds, ArrayList<PGPSecretKey> keys, ArrayList<GregorianCalendar> keysExpiryDates, ArrayList<Integer> keysUsages, String newPassPhrase, String oldPassPhrase) throws PgpGeneralException,
            PGPException, SignatureException, IOException {

        int usageId = keysUsages.get(0);
        boolean canSign;
        String mainUserId = userIds.get(0);

        PGPSecretKey masterKey = keys.get(0);

        // this removes all userIds and certifications previously attached to the masterPublicKey
        PGPPublicKey masterPublicKey = masterKey.getPublicKey();

        PGPSecretKeyRing mKR = ProviderHelper.getPGPSecretKeyRingByKeyId(mContext, masterKey.getKeyID());

        PBESecretKeyDecryptor keyDecryptor = new JcePBESecretKeyDecryptorBuilder().setProvider(
                Constants.BOUNCY_CASTLE_PROVIDER_NAME).build(oldPassPhrase.toCharArray());
        PGPPrivateKey masterPrivateKey = masterKey.extractPrivateKey(keyDecryptor);

        updateProgress(R.string.progress_certifying_master_key, 20, 100);
        int user_id_index = 0;
        for (String userId : userIds) {
                PGPContentSignerBuilder signerBuilder = new JcaPGPContentSignerBuilder(
                        masterPublicKey.getAlgorithm(), HashAlgorithmTags.SHA1)
                        .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME);
                PGPSignatureGenerator sGen = new PGPSignatureGenerator(signerBuilder);

                sGen.init(PGPSignature.POSITIVE_CERTIFICATION, masterPrivateKey);

                PGPSignature certification = sGen.generateCertification(userId, masterPublicKey);
                masterPublicKey = PGPPublicKey.addCertification(masterPublicKey, userId, certification);
            user_id_index++;
        }

        PGPKeyPair masterKeyPair = new PGPKeyPair(masterPublicKey, masterPrivateKey);

        PGPSignatureSubpacketGenerator hashedPacketsGen = new PGPSignatureSubpacketGenerator();
        PGPSignatureSubpacketGenerator unhashedPacketsGen = new PGPSignatureSubpacketGenerator();

        hashedPacketsGen.setKeyFlags(true, usageId);

        hashedPacketsGen.setPreferredSymmetricAlgorithms(true, PREFERRED_SYMMETRIC_ALGORITHMS);
        hashedPacketsGen.setPreferredHashAlgorithms(true, PREFERRED_HASH_ALGORITHMS);
        hashedPacketsGen.setPreferredCompressionAlgorithms(true, PREFERRED_COMPRESSION_ALGORITHMS);

        if (keysExpiryDates.get(0) != null) {
            GregorianCalendar creationDate = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
            creationDate.setTime(masterPublicKey.getCreationTime());
            GregorianCalendar expiryDate = keysExpiryDates.get(0);
            //note that the below, (a/c) - (b/c) is *not* the same as (a - b) /c
            //here we purposefully ignore partial days in each date - long type has no fractional part!
            long numDays = (expiryDate.getTimeInMillis() / 86400000) - (creationDate.getTimeInMillis() / 86400000);
            if (numDays <= 0)
                throw new PgpGeneralException(mContext.getString(R.string.error_expiry_must_come_after_creation));
            hashedPacketsGen.setKeyExpirationTime(false, numDays * 86400);
        } else {
            hashedPacketsGen.setKeyExpirationTime(false, 0); //do this explicitly, although since we're rebuilding,
            //this happens anyway
        }

        updateProgress(R.string.progress_building_master_key, 30, 100);

        // define hashing and signing algos
        PGPDigestCalculator sha1Calc = new JcaPGPDigestCalculatorProviderBuilder().build().get(
                HashAlgorithmTags.SHA1);
        PGPContentSignerBuilder certificationSignerBuilder = new JcaPGPContentSignerBuilder(
                masterKeyPair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA1);

        // Build key encrypter based on passphrase
        PBESecretKeyEncryptor keyEncryptor = new JcePBESecretKeyEncryptorBuilder(
                PGPEncryptedData.CAST5, sha1Calc)
                .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME).build(
                        newPassPhrase.toCharArray());

        PGPKeyRingGenerator keyGen = new PGPKeyRingGenerator(PGPSignature.POSITIVE_CERTIFICATION,
                masterKeyPair, mainUserId, sha1Calc, hashedPacketsGen.generate(),
                unhashedPacketsGen.generate(), certificationSignerBuilder, keyEncryptor);

        updateProgress(R.string.progress_adding_sub_keys, 40, 100);

        for (int i = 1; i < keys.size(); ++i) {
            updateProgress(40 + 50 * (i - 1) / (keys.size() - 1), 100);

            PGPSecretKey subKey = keys.get(i);
            PGPPublicKey subPublicKey = subKey.getPublicKey();

            PBESecretKeyDecryptor keyDecryptor2 = new JcePBESecretKeyDecryptorBuilder()
                    .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME).build(
                            oldPassPhrase.toCharArray());
            PGPPrivateKey subPrivateKey = subKey.extractPrivateKey(keyDecryptor2);

            // TODO: now used without algorithm and creation time?! (APG 1)
            PGPKeyPair subKeyPair = new PGPKeyPair(subPublicKey, subPrivateKey);

            hashedPacketsGen = new PGPSignatureSubpacketGenerator();
            unhashedPacketsGen = new PGPSignatureSubpacketGenerator();

            usageId = keysUsages.get(i);
            canSign = (usageId & KeyFlags.SIGN_DATA) > 0; //todo - separate function for this
            if (canSign) {
                Date todayDate = new Date(); //both sig times the same
                // cross-certify signing keys
                hashedPacketsGen.setSignatureCreationTime(false, todayDate); //set outer creation time
                PGPSignatureSubpacketGenerator subHashedPacketsGen = new PGPSignatureSubpacketGenerator();
                subHashedPacketsGen.setSignatureCreationTime(false, todayDate); //set inner creation time
                PGPContentSignerBuilder signerBuilder = new JcaPGPContentSignerBuilder(
                        subPublicKey.getAlgorithm(), PGPUtil.SHA1)
                        .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME);
                PGPSignatureGenerator sGen = new PGPSignatureGenerator(signerBuilder);
                sGen.init(PGPSignature.PRIMARYKEY_BINDING, subPrivateKey);
                sGen.setHashedSubpackets(subHashedPacketsGen.generate());
                PGPSignature certification = sGen.generateCertification(masterPublicKey,
                        subPublicKey);
                unhashedPacketsGen.setEmbeddedSignature(false, certification);
            }
            hashedPacketsGen.setKeyFlags(false, usageId);

            if (keysExpiryDates.get(i) != null) {
                GregorianCalendar creationDate = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
                creationDate.setTime(subPublicKey.getCreationTime());
                GregorianCalendar expiryDate = keysExpiryDates.get(i);
                //note that the below, (a/c) - (b/c) is *not* the same as (a - b) /c
                //here we purposefully ignore partial days in each date - long type has no fractional part!
                long numDays = (expiryDate.getTimeInMillis() / 86400000) - (creationDate.getTimeInMillis() / 86400000);
                if (numDays <= 0)
                    throw new PgpGeneralException(mContext.getString(R.string.error_expiry_must_come_after_creation));
                hashedPacketsGen.setKeyExpirationTime(false, numDays * 86400);
            } else {
                hashedPacketsGen.setKeyExpirationTime(false, 0); //do this explicitly, although since we're rebuilding,
                //this happens anyway
            }

            keyGen.addSubKey(subKeyPair, hashedPacketsGen.generate(), unhashedPacketsGen.generate());
        }

        PGPSecretKeyRing secretKeyRing = keyGen.generateSecretKeyRing();
        PGPPublicKeyRing publicKeyRing = keyGen.generatePublicKeyRing();

        updateProgress(R.string.progress_saving_key_ring, 90, 100);

        ProviderHelper.saveKeyRing(mContext, secretKeyRing);
        ProviderHelper.saveKeyRing(mContext, publicKeyRing);

        updateProgress(R.string.progress_done, 100, 100);
    }

    public void buildSecretKey(SaveKeyringParcel saveParcel) throws PgpGeneralException,
            PGPException, SignatureException, IOException {

        updateProgress(R.string.progress_building_key, 0, 100);
        PGPSecretKey masterKey = saveParcel.keys.get(0);

        PGPSecretKeyRing mKR = ProviderHelper.getPGPSecretKeyRingByKeyId(mContext, masterKey.getKeyID());
        PGPPublicKeyRing pKR = ProviderHelper.getPGPPublicKeyRingByKeyId(mContext, masterKey.getKeyID());

        if (saveParcel.oldPassPhrase == null) {
            saveParcel.oldPassPhrase = "";
        }
        if (saveParcel.newPassPhrase == null) {
            saveParcel.newPassPhrase = "";
        }

        if (mKR == null) {
            buildNewSecretKey(saveParcel.userIDs, saveParcel.keys, saveParcel.keysExpiryDates,
                    saveParcel.keysUsages, saveParcel.newPassPhrase, saveParcel.oldPassPhrase); //new Keyring
            return;
        }

        /*
        IDs -
            remove deleted ids
            if the primary ID changed we need to:
                remove all of the IDs from the keyring, saving their certifications
                add them all in again, updating certs of IDs which have changed
            else
                remove changed IDs and add in with new certs

        Keys
            remove deleted keys
            if a key is modified, re-sign it
                do we need to remove and add in?
         */

        for (PGPSecretKey dKey : saveParcel.deletedKeys) {
            mKR = PGPSecretKeyRing.removeSecretKey(mKR, dKey);
        }

        masterKey = mKR.getSecretKey();
        PGPPublicKey masterPublicKey = masterKey.getPublicKey();

        int usageId = saveParcel.keysUsages.get(0);
        boolean canSign;
        String mainUserId = saveParcel.userIDs.get(0);

        PBESecretKeyDecryptor keyDecryptor = new JcePBESecretKeyDecryptorBuilder().setProvider(
                Constants.BOUNCY_CASTLE_PROVIDER_NAME).build(saveParcel.oldPassPhrase.toCharArray());
        PGPPrivateKey masterPrivateKey = masterKey.extractPrivateKey(keyDecryptor);

        updateProgress(R.string.progress_certifying_master_key, 20, 100);

        for (String delID : saveParcel.deletedIDs) {
            masterPublicKey = PGPPublicKey.removeCertification(masterPublicKey, delID);
        }

        int user_id_index = 0;
        if (saveParcel.primaryIDChanged) {
            ArrayList<Pair<String, PGPSignature>> sigList = new ArrayList<Pair<String, PGPSignature>>();
            for (String userId : saveParcel.userIDs) {
                String orig_id = saveParcel.originalIDs.get(user_id_index);
                if (orig_id.equals(userId)) {
                    Iterator<PGPSignature> orig_sigs = masterPublicKey.getSignaturesForID(orig_id); //TODO: make sure this iterator only has signatures we are interested in
                    while (orig_sigs.hasNext()) {
                        PGPSignature orig_sig = orig_sigs.next();
                        sigList.add(new Pair<String, PGPSignature>(orig_id, orig_sig));
                    }
                } else {
                    PGPContentSignerBuilder signerBuilder = new JcaPGPContentSignerBuilder(
                            masterPublicKey.getAlgorithm(), HashAlgorithmTags.SHA1)
                            .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME);
                    PGPSignatureGenerator sGen = new PGPSignatureGenerator(signerBuilder);

                    sGen.init(PGPSignature.POSITIVE_CERTIFICATION, masterPrivateKey);

                    PGPSignature certification = sGen.generateCertification(userId, masterPublicKey);
                    sigList.add(new Pair<String, PGPSignature>(userId, certification));
                }
                masterPublicKey = PGPPublicKey.removeCertification(masterPublicKey, orig_id);
                user_id_index++;
            }
            for (Pair<String, PGPSignature> to_add : sigList) {
                masterPublicKey = PGPPublicKey.addCertification(masterPublicKey, to_add.first, to_add.second);
            }
        } else {
            for (String userId : saveParcel.userIDs) {
                String orig_id = saveParcel.originalIDs.get(user_id_index);
                if (!orig_id.equals(userId)) {
                    PGPContentSignerBuilder signerBuilder = new JcaPGPContentSignerBuilder(
                            masterPublicKey.getAlgorithm(), HashAlgorithmTags.SHA1)
                            .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME);
                    PGPSignatureGenerator sGen = new PGPSignatureGenerator(signerBuilder);

                    sGen.init(PGPSignature.POSITIVE_CERTIFICATION, masterPrivateKey);

                    PGPSignature certification = sGen.generateCertification(userId, masterPublicKey);
                    masterPublicKey = PGPPublicKey.removeCertification(masterPublicKey, orig_id);
                    masterPublicKey = PGPPublicKey.addCertification(masterPublicKey, userId, certification);
                }
                user_id_index++;
            }
        }

            // define hashing and signing algos
            PGPDigestCalculator sha1Calc = new JcaPGPDigestCalculatorProviderBuilder().build().get(
                    HashAlgorithmTags.SHA1);
            PGPContentSignerBuilder certificationSignerBuilder = new JcaPGPContentSignerBuilder(
                    masterKeyPair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA1);

            // Build key encrypter based on passphrase
            PBESecretKeyEncryptor keyEncryptor = new JcePBESecretKeyEncryptorBuilder(
                    PGPEncryptedData.CAST5, sha1Calc)
                    .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME).build(
                            newPassphrase.toCharArray());
        }

        hashedPacketsGen.setKeyFlags(true, usageId);

        hashedPacketsGen.setPreferredSymmetricAlgorithms(true, PREFERRED_SYMMETRIC_ALGORITHMS);
        hashedPacketsGen.setPreferredHashAlgorithms(true, PREFERRED_HASH_ALGORITHMS);
        hashedPacketsGen.setPreferredCompressionAlgorithms(true, PREFERRED_COMPRESSION_ALGORITHMS);

        if (saveParcel.keysExpiryDates.get(0) != null) {
            GregorianCalendar creationDate = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
            creationDate.setTime(masterPublicKey.getCreationTime());
            GregorianCalendar expiryDate = saveParcel.keysExpiryDates.get(0);
            //note that the below, (a/c) - (b/c) is *not* the same as (a - b) /c
            //here we purposefully ignore partial days in each date - long type has no fractional part!
            long numDays = (expiryDate.getTimeInMillis() / 86400000) - (creationDate.getTimeInMillis() / 86400000);
            if (numDays <= 0)
                throw new PgpGeneralException(mContext.getString(R.string.error_expiry_must_come_after_creation));
            hashedPacketsGen.setKeyExpirationTime(false, numDays * 86400);
        } else {
            hashedPacketsGen.setKeyExpirationTime(false, 0); //do this explicitly, although since we're rebuilding,
                                                             //this happens anyway
        }

        updateProgress(R.string.progress_building_master_key, 30, 100);

        // define hashing and signing algos
        PGPDigestCalculator sha1Calc = new JcaPGPDigestCalculatorProviderBuilder().build().get(
                HashAlgorithmTags.SHA1);
        PGPContentSignerBuilder certificationSignerBuilder = new JcaPGPContentSignerBuilder(
                masterKeyPair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA1);

        // Build key encrypter based on passphrase
        PBESecretKeyEncryptor keyEncryptor = new JcePBESecretKeyEncryptorBuilder(
                PGPEncryptedData.CAST5, sha1Calc)
                .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME).build(
                        saveParcel.newPassPhrase.toCharArray());

        PGPKeyRingGenerator keyGen = new PGPKeyRingGenerator(PGPSignature.POSITIVE_CERTIFICATION,
                masterKeyPair, mainUserId, sha1Calc, hashedPacketsGen.generate(),
                unhashedPacketsGen.generate(), certificationSignerBuilder, keyEncryptor);

        for (int i = 0; i < saveParcel.keys.size(); ++i) {
            updateProgress(40 + 50 * (i - 1) / (saveParcel.keys.size() - 1), 100);
            if (saveParcel.moddedKeys[i]) {
//secretkey.replacepublickey with updated public key
//secretkeyring.insertsecretkey with newly signed secret key
            } else {
//else nothing, right?
            }
            if (saveParcel.newKeys[i]) {
                //set the passphrase to the old one, so we can update the whole keyring passphrase later
                PBESecretKeyEncryptor keyEncryptorOld = new JcePBESecretKeyEncryptorBuilder(
                        PGPEncryptedData.CAST5, sha1Calc)
                        .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME).build(
                                saveParcel.oldPassPhrase.toCharArray());
                PBESecretKeyDecryptor keyDecryptorBlank = new JcePBESecretKeyDecryptorBuilder()
                        .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME).build(
                                saveParcel.oldPassPhrase.toCharArray());
                saveParcel.keys.set(i, PGPSecretKey.copyWithNewPassword(saveParcel.keys.get(i),
                        keyDecryptorBlank, keyEncryptorOld));
            }
            //finally, update the keyrings
            mKR = PGPSecretKeyRing.insertSecretKey(mKR, saveParcel.keys.get(i));
            pKR = PGPPublicKeyRing.insertPublicKey(pKR, saveParcel.keys.get(i).getPublicKey());
        }
        updateProgress(R.string.progress_adding_sub_keys, 40, 100);

        for (int i = 1; i < saveParcel.keys.size(); ++i) {
            updateProgress(40 + 50 * (i - 1) / (saveParcel.keys.size() - 1), 100);

            Key key = keys.get(i);
            PGPSecretKey subSecretKey = key.getSecretKey();
            PGPPublicKey subPublicKey = key.getPublicKey();

            PGPPrivateKey subPrivateKey = key.extractPrivateKey(saveParcel.oldPassphrase);

            // TODO: now used without algorithm and creation time?! (APG 1)
            PGPKeyPair subKeyPair = new PGPKeyPair(subPublicKey, subPrivateKey);

            PGPSignatureSubpacketGenerator hashedPacketsGen = new PGPSignatureSubpacketGenerator();
            PGPSignatureSubpacketGenerator unhashedPacketsGen = new PGPSignatureSubpacketGenerator();

            usageId = saveParcel.keysUsages.get(i);
            canSign = (usageId & KeyFlags.SIGN_DATA) > 0; //todo - separate function for this
            if (canSign) {
                Date todayDate = new Date(); //both sig times the same
                // cross-certify signing keys
                hashedPacketsGen.setSignatureCreationTime(false, todayDate); //set outer creation time
                PGPSignatureSubpacketGenerator subHashedPacketsGen = new PGPSignatureSubpacketGenerator();
                subHashedPacketsGen.setSignatureCreationTime(false, todayDate); //set inner creation time
                PGPContentSignerBuilder signerBuilder = new JcaPGPContentSignerBuilder(
                        subPublicKey.getAlgorithm(), PGPUtil.SHA1)
                        .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME);
                PGPSignatureGenerator sGen = new PGPSignatureGenerator(signerBuilder);
                sGen.init(PGPSignature.PRIMARYKEY_BINDING, subPrivateKey);
                sGen.setHashedSubpackets(subHashedPacketsGen.generate());
                PGPSignature certification = sGen.generateCertification(masterPublicKey,
                        subPublicKey);
                unhashedPacketsGen.setEmbeddedSignature(false, certification);
            }
            hashedPacketsGen.setKeyFlags(false, usageId);

            if (saveParcel.keysExpiryDates.get(i) != null) {
                GregorianCalendar creationDate = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
                creationDate.setTime(subPublicKey.getCreationTime());
                GregorianCalendar expiryDate = saveParcel.keysExpiryDates.get(i);
                //note that the below, (a/c) - (b/c) is *not* the same as (a - b) /c
                //here we purposefully ignore partial days in each date - long type has no fractional part!
                long numDays = expiryDate.getTimeInMillis() / 86400000 -
                                    creationDate.getTimeInMillis() / 86400000;
                if (numDays <= 0) {
                    throw new PgpGeneralException(
                        mContext.getString(R.string.error_expiry_must_come_after_creation));
                }
                hashedPacketsGen.setKeyExpirationTime(false, numDays * 86400);
            } else {
                // do this explicitly, although since we're rebuilding, this happens anyway
                hashedPacketsGen.setKeyExpirationTime(false, 0);
            }

            keyGen.addSubKey(subKeyPair, hashedPacketsGen.generate(), unhashedPacketsGen.generate());
        }

        //update the passphrase
        mKR = PGPSecretKeyRing.copyWithNewPassword(mKR, keyDecryptor, keyEncryptor);
        updateProgress(R.string.progress_saving_key_ring, 90, 100);

        ProviderHelper.saveKeyRing(mContext, mKR);
        ProviderHelper.saveKeyRing(mContext, pKR);

        updateProgress(R.string.progress_done, 100, 100);
    }

    /**
     * Certify the given pubkeyid with the given masterkeyid.
     *
     * @param masterKeyId Certifying key, must be available as secret key
     * @param pubKeyId ID of public key to certify
     * @param userIds User IDs to certify, must not be null or empty
     * @param passphrase Passphrase of the secret key
     * @return A keyring with added certifications
     */
    public PGPPublicKeyRing certifyKey(long masterKeyId, long pubKeyId, List<String> userIds,
                                        String passphrase)
            throws PgpGeneralException, NoSuchAlgorithmException, NoSuchProviderException,
            PGPException, SignatureException {
        if (passphrase == null) {
            throw new PgpGeneralException("Unable to obtain passphrase");
        } else {

            // create a signatureGenerator from the supplied masterKeyId and passphrase
            PGPSignatureGenerator signatureGenerator; {

                PGPSecretKey certificationKey = PgpKeyHelper.getCertificationKey(mContext, masterKeyId);
                if (certificationKey == null) {
                    throw new PgpGeneralException(mContext.getString(R.string.error_signature_failed));
                }

                PBESecretKeyDecryptor keyDecryptor = new JcePBESecretKeyDecryptorBuilder().setProvider(
                        Constants.BOUNCY_CASTLE_PROVIDER_NAME).build(passphrase.toCharArray());
                PGPPrivateKey signaturePrivateKey = certificationKey.extractPrivateKey(keyDecryptor);
                if (signaturePrivateKey == null) {
                    throw new PgpGeneralException(
                            mContext.getString(R.string.error_could_not_extract_private_key));
                }

                // TODO: SHA256 fixed?
                JcaPGPContentSignerBuilder contentSignerBuilder = new JcaPGPContentSignerBuilder(
                        certificationKey.getPublicKey().getAlgorithm(), PGPUtil.SHA256)
                        .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME);

                signatureGenerator = new PGPSignatureGenerator(contentSignerBuilder);
                signatureGenerator.init(PGPSignature.DEFAULT_CERTIFICATION, signaturePrivateKey);
            }

            { // supply signatureGenerator with a SubpacketVector
                PGPSignatureSubpacketGenerator spGen = new PGPSignatureSubpacketGenerator();
                PGPSignatureSubpacketVector packetVector = spGen.generate();
                signatureGenerator.setHashedSubpackets(packetVector);
            }

            // fetch public key ring, add the certification and return it
            PGPPublicKeyRing pubring = ProviderHelper
                    .getPGPPublicKeyRingByKeyId(mContext, pubKeyId);
            PGPPublicKey signedKey = pubring.getPublicKey(pubKeyId);
            for (String userId : new IterableIterator<String>(userIds.iterator())) {
                PGPSignature sig = signatureGenerator.generateCertification(userId, signedKey);
                signedKey = PGPPublicKey.addCertification(signedKey, userId, sig);
            }
            pubring = PGPPublicKeyRing.insertPublicKey(pubring, signedKey);

            return pubring;
        }
    }
}
