package org.cdlib.xtf.textIndexer;

/**
 * Copyright (c) 2004, Regents of the University of California
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without 
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, 
 *   this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, 
 *   this list of conditions and the following disclaimer in the documentation 
 *   and/or other materials provided with the distribution.
 * - Neither the name of the University of California nor the names of its
 *   contributors may be used to endorse or promote products derived from this 
 *   software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" 
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE 
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE 
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE 
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR 
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF 
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS 
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN 
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) 
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE 
 * POSSIBILITY OF SUCH DAMAGE.
 */

import java.io.File;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.Hits;

import org.cdlib.xtf.textEngine.IdxConfigUtil;
import org.cdlib.xtf.util.Path;
import org.cdlib.xtf.util.Trace;

import java.io.IOException;


////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////

/**
 * This class provides a simple mechanism for removing documents from an index
 * when the source text no longer exists in the document library. <br><br>
 * 
 * This class locates all the summary chunks for documents in an index, and
 * checks to see if the associated source text files exist. If a source text
 * file no longer exists for an indexed document, the summary and text chunks 
 * for that document are removed from the index. <br><br>
 * 
 * To use this class, simply instantiate a copy, and call the 
 * {@link #cullIndex(File,IndexInfo) cullIndex()}
 * method on a directory containing an index. Note that the directory passed
 * may also be a root directory with many index sub-directories if desired.
 */

public class IdxTreeCuller

{
         
  ////////////////////////////////////////////////////////////////////////////

  /**
   * Create an <code>IdxTreeCuller</code> instance and call this method to 
   * remove documents from indices when the associated source text no longer 
   * exists. <br><br>
   *                     
   * Performs the actual work of removing missing documents from an index.   
   * <br><br>
   *                     
   * @param  xtfHome        The base directory relative to which file paths
   *                        are interpreted.
   *                        <br><br>
   * 
   * @param  idxInfo        The index to cull.
   *                        <br><br>
   * 
   * @throws Exception      Passes back any exceptions generated by Lucene 
   *                        during the opening of, reading of, or writing to 
   *                        the specified index.
   *                        <br><br>
   * 
   */

  public void cullIndex( 
  
      File      xtfHome,
      IndexInfo idxInfo 
  
  ) throws Exception
  
  {
    // Start with no Path fields encountered, and no documents culled.
    int docCount  = 0;
    int cullCount = 0;
    
    try {
        
        // Try to open the index for reading. If we fail and 
        // throw, skip the index.
        //
        File idxFile = Path.resolveRelOrAbs(xtfHome, idxInfo.indexPath);
        String idxPath = Path.normalizePath( idxFile.getCanonicalPath() );
        IndexReader indexReader = IndexReader.open( idxPath );
        
        // Get a list of all the "header" chunks for documents in this
        // index (i.e., documents with a "docInfo" field.)
        //
        TermQuery     docQuery = new TermQuery( new Term("docInfo", "1") );

        IndexSearcher indexSearcher = new IndexSearcher( indexReader );
        Hits hits = indexSearcher.search( docQuery );
        
        // Step through each of the documents found.
        for( int i = 0; i < hits.length(); i++ ) {
            
            // Get the current document.
            Document doc = indexReader.document( hits.id(i) );
          
            // Get the key, which contains the index name and the path from its
            // source directory.
            //
            String key = doc.get( "key" );
            assert key.indexOf(':') >= 0 : "Invalid index key - missing ':'";
            String indexName = key.substring( 0, key.indexOf(':') );
            String relPath   = key.substring( key.indexOf(':') + 1 );
            
            // Skip documents that aren't part of the index we want.
            if( !indexName.equals(idxInfo.indexName) )
                continue;
            
            // If a subdirectory was specified, skip docs that aren't in it.
            if( idxInfo.subDir != null && !relPath.startsWith(idxInfo.subDir) )
                continue;
            
            // Track how many documents there are.
            docCount++;
          
            // Create a reference to the source XML document.
            File sourceDir = Path.resolveRelOrAbs( xtfHome, 
                                                   idxInfo.sourcePath );
            File currFile = Path.resolveRelOrAbs( sourceDir, relPath );
             
            // If the source XML document doesn't exist...
            if( !currFile.exists() ) {
                
                // Indicate which document we're looking at.
                Trace.tab();
                Trace.info( "[" + relPath +"] ... " );
                
                // Delete all chunks for the missing document.
                int nDel = indexReader.delete( new Term( "key", key ) );
                
                // If no chunks were deleted, something's wrong, so bail.
                if( nDel == 0 ) {
                    
                    // Create an exception that we can throw.
                    TextIndexerException e = 
                        new TextIndexerException( "*** Error: Unable to " +
                        "delete chunks from index." );
                    
                    // Output an error message.
                    Trace.tab();  
                    Trace.error( e.getMessage() );
                    Trace.untab();
                    
                    // And throw the exception.
                    throw e;
                }
                 
                // Also delete the lazy file, if any. Might as well delete
                // empty parent directories as well.
                //
                File lazyFile = 
                    IdxConfigUtil.calcLazyPath( xtfHome,
                                                idxInfo, 
                                                currFile, 
                                                false );
                if( !Path.deletePath(lazyFile.toString()) )
                    Trace.warning( "Could not delete lazy-tree file" );
                
                ///////////////////////////
                //   Diagnostic Output   //
                ///////////////////////////
                //
                Trace.tab();
                Trace.debug( "Deleted " + nDel + "Chunks." );
                Trace.untab();
                
                // Track how many documents we've culled.
                cullCount++;           
            
                Trace.more( Trace.info, "Missing: Removed from Index." );
                
                Trace.untab();
                
            } // if( !currFile.exists() )
        
        } // for( int i... )
        
        // Close up the index reader and searcher
        indexSearcher.close();
        indexReader.close();
        
        // Now if the number of documents encounted equals the number
        // of documents deleted, there's a good chance the index is 
        // empty and we can delete the whole index directory.
        //
        boolean indexDeleted = false;
        if( docCount == cullCount ) {
            
            boolean anyNotDeleted = false;
            for( int i = 1; i < indexReader.maxDoc(); i++ ) {
                if( !indexReader.isDeleted(i) ) {
                    anyNotDeleted = true;
                    break;
                }
            }

            if( !anyNotDeleted ) {
                deleteIndex( Path.resolveRelOrAbs(xtfHome, 
                                                  idxInfo.indexPath) );
                indexDeleted = true;
            }
        } // if( docCount == cullCount )
        
        // The current index isn't empty, but if we deleted a
        // document from it, say so.
        //
        if( cullCount == 1 )
            Trace.info( cullCount + " Missing Document Removed." );
        
        // Likewise, if we deleted more than one document, say so.
        else if( cullCount >  1 )
            Trace.info( cullCount + " Missing Documents Removed." );
        
        // If we didn't delete any documents from the directory, say so.
        else 
            Trace.info( "No Missing Documents to Remove." );
        
        // If the entire index was deleted, say so.
        if( indexDeleted )
            Trace.info( "Empty Index Deleted." );  
        
    } //  try( to open the specified index )
    
    catch ( Exception e ) {
      
        // Log the problem.
        Trace.error( "Skipped Due to Errors." );
        
        // Pass the exception on.
        throw e;  
    }
    
  } // cullIndex()


  ////////////////////////////////////////////////////////////////////////////
  
  private void deleteIndex( File idxDirToCull )
    throws IOException 
  {
    int deleteFailCount = 0;
    
    // First, we need to delete all the files in the index 
    // directory, before we can delete the directory itself.
    //
    File[] fileList = idxDirToCull.listFiles();
    
    // Delete the files.
    for( int j = 0; j < fileList.length; j++ ) {
        
        // Try to delete the current file.
        try { fileList[j].delete(); } 
        
        // If we could not, display a warning and track the delete
        // failure count.
        //
        catch( Exception e ) {
            Trace.tab();
            Trace.warning( "*** Warning: Unable to Delete [ " +
                           fileList[j].getCanonicalPath() + " ]." );
            Trace.untab();
            deleteFailCount++;                 
        }
    
    } // for( int j = 0; j < fileList.length; j++ )
    
    // If some files couldn't be deleted, there's no point in
    // continuing, so stop gracefully now.
    //
    if( deleteFailCount > 0 ) {
        if( deleteFailCount > 1 )
            Trace.info( "Empty Index not deleted because "    +
                        deleteFailCount + " files could not " +
                        "be removed from index directory." );
        else
            Trace.info( "Empty Index not deleted because "  +
                        "a file could not be removed from " +
                        "index directory." );  
        return;
    }
    
    // Now start with the index directory...
    File dir = idxDirToCull;
    
    // And delete it and all the empty parent directories
    // above it.
    //
    for(;;) {
      
        // If the current directory is not empty, we're done.
        File[] contents = dir.listFiles();
        if( contents.length != 0 ) break;
        
        // Otherwise, hang on to the parent directory for 
        // the current directory.
        //
        File parentDir = dir.getParentFile();
        
        // Try to delete the current directory.
        try { dir.delete(); }
        
        // If we could not, display a warning and end gracefully,
        // since we can't continue to delete parent directories if
        // the current one can't be deleted.
        //
        catch( Exception e ) {
          
            Trace.tab();
            Trace.info( "*** Warning: Unable to delete empty "       +
                        "index directory [" + dir.getCanonicalPath() +
                        "]." );
            Trace.untab();
            return;
        
        } // catch( Exception e )
        
        // Then back up to the parent and repeat.
        dir = parentDir;
    
    } // for(;;)
    
  } // deleteIndex()
  
} // class IdxTreeCuller
