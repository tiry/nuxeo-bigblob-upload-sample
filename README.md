nuxeo-bigblob-upload-sample
===========================

## What is this project ?

This is just a sample of code to see how Blob can be attached to a Nuxeo Document outside of the boundaries of the transaction.

## Why would I want that ?

By default, when you create a Document and attach Blob(s), everything is done within the same transaction.

If you have big streams, the transfer time associated to these big stream will make the transaction run during a long time.

As a result, you may end up having :

 - transaction timeout
 - deadlocks
 
## How does it work ?

Inside the Repository, meta-data and streams are stored separately :

 - meta-data are inside the DB (SQL or Mongo)
 - stream are on FileSystem, S3 ...
 
The idea is to feed the repository in 2 steps :

 - pre-fill the Binary Store
     - upload the stream via a system batch
     - upload the stream in java code, but in a dedicated transaction 
 - give enough information to the DB so that it can reference the stream without having to actually process it
 
The trick is to use `StorageBlob` as class for storing the Blob inside the Document.
This class does not actually reference the stream, but the informations contained inside the BinaryManager.

The test shows 2 ways of pre-filling the BinaryManager :

 - Java way : use directly the BinaryManager API (to be used in a dedicated transaction)
 - System way : we get the info about the BinaryManager and do all the digest/copy work *"by hand"*

## Building

Running the tests

    mvn clean test 
    
Generate the eclipse project

    mvn eclipse:eclipse
