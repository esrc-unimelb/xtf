package org.cdlib.xtf.test;

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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Vector;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;

import org.cdlib.xtf.crossQuery.CrossQuery;
import org.cdlib.xtf.lazyTree.SearchTree;
import org.cdlib.xtf.textEngine.DocHit;
import org.cdlib.xtf.textEngine.IdxConfigUtil;
import org.cdlib.xtf.textEngine.NgramQueryRewriter;
import org.cdlib.xtf.textEngine.QueryProcessor;
import org.cdlib.xtf.textEngine.QueryRequest;
import org.cdlib.xtf.textEngine.QueryResult;
import org.cdlib.xtf.textEngine.Snippet;
import org.cdlib.xtf.textIndexer.NgramStopFilter;
import org.cdlib.xtf.textIndexer.TextIndexer;
import org.cdlib.xtf.util.Attrib;
import org.cdlib.xtf.util.CircularQueue;
import org.cdlib.xtf.util.DiskHashWriter;
import org.cdlib.xtf.util.IntHash;
import org.cdlib.xtf.util.Path;
import org.cdlib.xtf.util.StructuredFile;
import org.cdlib.xtf.util.Trace;
import org.cdlib.xtf.util.XMLWriter;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Runs a series of regression tests. These take a set of test files and index
 * them, then run queries and check the results.<br><br>
 * 
 * The test scans subdirectories of the directory it's started in, looking
 * for:
 * 
 * <ul>
 *      <li>IndexConfig.xml - tells what to index and how</li>
 *      <li>xxx-in.xml - An input query (same format as output by queryGen.xsl)</li>
 *      <li>xxx-gold.xml - The expected output hits from the query</li>
 * 
 * Note that the index for a given directory will be built before all query 
 * tests in that directory are run.
 * 
 * @author Martin Haye
 */
public class RegressTest
{
    int nRun       = 0;
    int nSucceeded = 0;
    String baseDir;
    File filterDir;
    File filterFile;
    LinkedList failedTests = new LinkedList();
    
    public static void main( String[] args )
    {
        // Make sure assertions are enabled.
        boolean ok = false;
        assert ok = true; // Intentional side-effect
        if( !ok ) {
            Trace.error( 
                "Error: Regression requires assertions to be enabled.\n" +
                "       Pass -enableassertions command-line switch to JVM." );
            System.exit( 1 );
        }
        
        // Test the libraries we depend on.
        StructuredFile.tester.test();
        IntHash.tester.test();
        DiskHashWriter.tester.test();
        CircularQueue.tester.test();
        NgramStopFilter.tester.test();
        NgramQueryRewriter.tester.test();
        
        // Go for it.
        RegressTest test = new RegressTest();
        test.run( args );
        
        // All done.
        System.exit( 0 );
    } // main()
    
    public void run( String[] args )
    {
        // For some evil reason, Resin overrides the default transformer
        // and document builder implementations with its own, deeply inferior,
        // versions. Screw that.
        //
        System.setProperty( "javax.xml.parsers.TransformerFactory",
                            "net.sf.saxon.TransformerFactoryImpl" );
        System.setProperty( "javax.xml.parsers.DocumentBuilderFactory",
                            "net.sf.saxon.om.DocumentBuilderFactoryImpl" );
        
        try {
            // If a specific test was specified, set that as a filter.
            if( args.length > 0 ) {
                filterFile = new File(args[0]).getCanonicalFile();
                if( !filterFile.exists() )
                    filterFile = new File(args[0] + "-in.xml").getCanonicalFile();
                if( !filterFile.exists() ) {
                    Trace.error( "Unable to locate " + args[0] );
                    System.exit( 1 );
                }
                     
                if( filterFile.isDirectory() ) {
                    filterDir = filterFile;
                    filterFile = null;
                }
                else
                    filterDir = filterFile.getParentFile();
            }
            
            // Start from the working directory, and scan for regress 
            // sub-directories.
            //
            processDir( new File(System.getProperty("user.dir")),
                        new LinkedList() );
        }
        catch( Exception e ) {
            Trace.error( "Unexpected regress error: " + e );
        }
        
        if( nRun == 0 )
            Trace.info( "\nNo tests found to run." );
        else if( nRun == nSucceeded )
            Trace.info( "\nAll " + nRun + " tests passed." );
        else
            Trace.info( "\n" + nRun + " tests ran, but " + 
                        (nRun-nSucceeded) + " failed." );
        
        if( !failedTests.isEmpty() ) {
            Trace.warning( "Failed tests:" );
            while( !failedTests.isEmpty() )
                Trace.warning( failedTests.removeFirst().toString() );
        }
    } // run()

    private void processDir( File curFile, LinkedList inFiles )
        throws IOException
    {
        if( baseDir == null )
            baseDir = curFile.toString();

        // If the file we were passed was in fact a directory...
        if( curFile.isDirectory() ) {
            
            // Get the list of files it contains.
            String[] files = curFile.list();

            // And process each of them.
            LinkedList newInFiles = new LinkedList();
            for( int i = 0; i < files.length; i++ )
                processDir( new File(curFile, files[i]), newInFiles );
            
            // If we have some input files for tests to run, run them now.
            if( newInFiles.isEmpty() )
                return;
            
            runTests( newInFiles );
            return;
        }
       
        // If it's an index config file, index it.
        String path = curFile.toString();
        if( path.endsWith("IndexConfig.xml") ) {
            if( filterDir == null || curFile.getParentFile().equals(filterDir) )
                index( curFile );
            return;
        }
        
        // If it's an input file, queue it for processing after the index is
        // built.
        //
        if( path.endsWith("-in.xml") ) {
            if( (filterDir == null || curFile.getParentFile().equals(filterDir))
                && (filterFile == null || curFile.equals(filterFile)) )
                inFiles.add( curFile );
        }
        
    } // processDir()
    
    /**
     * Runs a configuration file to produce an index. Note that we always do
     * a "clean" index.
     * 
     * @param configFile    Path to the config file.
     */
    private void index( File configFile )
        throws IOException
    {
        String dir = configFile.getParentFile().toString();
        System.setProperty( "user.dir", new File(dir).getCanonicalPath() );
        
        // Blow away the old index directory.
        File indexDir = new File(dir, "IndexDB" );
        Path.deleteDir( indexDir );
        
        // Set the xtf.home property to the regression directory.
        System.setProperty( "xtf.home", dir );
        
        // Make a command-line to run the indexer.
        String[] args = new String[] { "-trace", "info",
                                       "-config", "", "-clean", 
                                       "-index", "all" }; 
        args[3] = configFile.toString();
        TextIndexer.main( args );
        Trace.info( "" );
        Trace.info( "" );
    } // index()
    

    private void runTests( LinkedList inFiles )
        throws IOException
    {
        // Process each test in turn.
        while( !inFiles.isEmpty() ) {
            File inFile = (File) inFiles.removeFirst();
            runTest( inFile );
        }
    }
    
    private String chopPath( String in )
    {
        if( in.startsWith(baseDir) )
            in = in.substring( baseDir.length() );
        if( in.endsWith("-in.xml") )
            in = in.substring( 0, in.length() - 7 );
        if( in.startsWith("\\") || in.startsWith("/") )
            in = in.substring( 1 );
        return "\"" + in + "\"";
    }
    
    private void runTest( File inFile )
        throws IOException
    {
        ++nRun;
        
        String dir = inFile.getParentFile().toString();
        System.setProperty( "user.dir", dir );
        
        String inFilePath = inFile.toString();
        Trace.info( "Running test " + chopPath(inFilePath) + "..." );
        
        String testFilePath = inFilePath.replaceAll( "-in", "-test" );
        File testFile = new File(testFilePath);
        
        // Okay, read the input file. It contains a query.
        Document queryDoc = null;
        try {
            DocumentBuilderFactory fac = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = fac.newDocumentBuilder();
            queryDoc = builder.parse( inFile );
        }
        catch( Exception e ) {
            Trace.error( "Unexpected exception while reading " + 
                                chopPath(inFilePath) + ":\n" + e );
            return;
        }

        // It may also contain a marker telling us to do a search-annotated
        // tree rather than a CrossQuery-style output.
        //
        try {
            String         inSpec    = readFile( inFile );
            QueryProcessor processor = new QueryProcessor();
            Element        root      = queryDoc.getDocumentElement();
            QueryRequest   request   = new QueryRequest( root, new File(dir) );

            if( inSpec.indexOf("regress-search-tree") >= 0 ) {
    
                // Get the document path
                int n, n2;
                n = inSpec.indexOf( "regress-search-tree" );
                n = inSpec.indexOf( "=\"", n ) + 2;
                n2 = inSpec.indexOf( "\"", n );
                String docPath = Path.normalizeFileName(
                                   dir + "/" + inSpec.substring(n, n2) );
                
                // Calculate the persistent path.
                String configPath = Path.normalizeFileName(
                                       dir + "/" + "IndexConfig.xml" );
                File lazyFile = IdxConfigUtil.calcLazyPath(
                                               inFile.getParentFile(),
                                               new File(configPath),
                                               "all",
                                               new File(docPath),
                                               false );
                
                // Load and search the document. We suppress scores in the
                // snippets because the scoring algorithm changes often and
                // we don't really care exactly what the scores are.
                //
                String docKey = IdxConfigUtil.calcDocKey(
                                               inFile.getParentFile(),
                                               new File(configPath),
                                               "all",
                                               new File(docPath) );
                SearchTree tree = new SearchTree( docKey, lazyFile ); 
                tree.suppressScores( true );
                tree.search( processor, request );
                
                // Output the resultant tree.
                OutputStreamWriter ow = new OutputStreamWriter(
                    new FileOutputStream(testFile), "UTF-8" );
                PrintWriter out = new PrintWriter( ow );
                String strVersion = XMLWriter.toString( (Node)tree );
                out.println( strVersion );
                out.close();
            }
            else
            {
                // Now run the query to obtain hits.
                QueryResult result = 
                    processor.processReq( new QueryRequest(root, new File(dir)) );
                
                // Write the hits to a file.
                writeHits( testFile, result );
            }
        }
        catch( Exception e ) {
            Trace.error( "Unexpected exception processing " +
                         chopPath(inFilePath) + ":\n" + e );
            return;
        }

        // See if there's a gold file, and if so, compare it.
        String goldFilePath = inFilePath.replaceAll( "-in", "-gold" );
        File goldFile = new File(goldFilePath);
        if( !goldFile.exists() ) {
            Trace.info( "Missing gold file. Test result was:\n" +
                                readFile(testFile) );
            Trace.error( "   ...test " + chopPath(inFilePath) + 
                                " failed (missing gold file).\n" );
            failedTests.add( chopPath(inFilePath) );
        }
        else if( !filesEqual(goldFile, testFile) ) {
            Trace.error( "   ...Incorrect result. Test " +
                                chopPath(inFilePath) + 
                                " failed!\n" );
            failedTests.add( chopPath(inFilePath) );
        }
        else {
            Trace.info( "   ...ok" );
            ++nSucceeded;
        }
    } // runTests()
    
    
    private void writeHits( File outFile, QueryResult result )
        throws IOException
    {
        Source hitDoc = structureHits( result, false );
        PrintWriter out = new PrintWriter( new OutputStreamWriter(
                                new FileOutputStream(outFile), "UTF-8") );
        out.println( XMLWriter.toString(hitDoc) );
        out.close();
    } // writeHits()
    
    /**
     * Makes an XML document out of the list of document hits, and returns a
     * Source object that represents it.
     */
    private Source structureHits( QueryResult result, boolean includeScores )
    {
        StringBuffer buf = new StringBuffer( 1000 );
        
        buf.append( "<crossQueryResult " +
                    "totalDocs=\"" + result.totalDocs + "\" " +
                    "startDoc=\"" + 
                        (result.totalDocs > 0 ? result.startDoc+1 : 0) + "\" " + 
                        // Note above: 1-based start
                    "endDoc=\"" + result.endDoc + "\">" );
        
        for( int i = 0; i < result.docHits.length; i++ ) {
            DocHit docHit = result.docHits[i];
            String key = docHit.filePath();
            assert key.indexOf(':') >= 0 : "Invalid key - missing ':'";
            String after = key.substring( key.indexOf(':') + 1 );
            buf.append( "<docHit file=\"" + after + "\">" );
            if( !docHit.metaData().isEmpty() ) {
                buf.append( "<meta>" );
                for( Iterator atts = docHit.metaData().iterator(); atts.hasNext(); )
                {
                    Attrib attrib = (Attrib) atts.next();
                    buf.append( "<" + attrib.key + ">" );
                    buf.append( attrib.value );
                    buf.append( "</" + attrib.key + ">" );
                } // for atts
                buf.append( "</meta>" );
            }
            
            for( int j = 0; j < docHit.nSnippets(); j++ )
            {    
                Snippet  snippet = docHit.snippet( j, true );
                buf.append( "<snippet" );
                if( snippet.sectionType != null )
                    buf.append( " sectionType=\"" + snippet.sectionType + "\"" );
                buf.append( ">" + 
                    CrossQuery.makeHtmlString(snippet.text, true) + 
                    "</snippet>" );
            } // for chunks
            buf.append( "</docHit>" );
        } // for i
        
        buf.append( "</crossQueryResult>" );
        
        // Now parse that into a document that can be fed to the stylesheet.
        String str = buf.toString();
        return new StreamSource( new StringReader(str) );
        
    } // structureHits()
    
    /**
     * Breaks up a string by newlines into an array of strings, one per line.
     * 
     * @param str   String to break up
     * @return      Array of the lines
     */
    String[] slurp( String str )
    {
        BufferedReader br = new BufferedReader( new StringReader(str) );
        Vector lines = new Vector( 100 );
        while( true ) {
            try {
                String line = br.readLine();
                if( line == null )
                    break;
                lines.add( line );
            }
            catch( IOException e ) {
                assert false : "String reader should never have IO exception";
                throw new RuntimeException( e );
            }
        } // while
        
        return (String[]) lines.toArray( new String[lines.size()] );
    } // slurp()
    
    /**
     * Compares two strings for equality. If not the same, a message
     * is printed.
     * 
     * @param result1   First string
     * @param result2   Second string
     * @return          true if equal, false if not.
     */
    private boolean sameResults( String result1, String result2 )
    {
      if( result1.equals(result2) )
          return true;
      
      Trace.info( "\n*** Mismatch! ***" );
      return false;
    } // sameResults()
    
    private boolean filesEqual( File file1, File file2 )
        throws IOException
    {
        String s1 = readFile( file1 );
        String s2 = readFile( file2 );
        return sameResults( s1, s2 );
    } // filesEqual()
    
    private String readFile( File file )
        throws IOException
    {
        InputStreamReader reader = new InputStreamReader(
            new FileInputStream(file), "UTF-8" );
        int size = (int) file.length();
        char[] buf = new char[size+1];
        int got = reader.read( buf );
        if( got == size+1 || got == 0 )
            throw new IOException( "Error reading file " + file.toString() );
        reader.close();
        return new String(buf, 0, got).replaceAll("\r\n", "\n");
    } // readFile()
    
} // class RegressTest