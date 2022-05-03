package org.yamcs.xtce.xlsv6;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.yamcs.YConfiguration;
import org.yamcs.mdb.XtceDbFactory;
import org.yamcs.xtce.AbsoluteTimeParameterType;
import org.yamcs.xtce.Algorithm;
import org.yamcs.xtce.CommandVerifier;
import org.yamcs.xtce.FloatDataEncoding;
import org.yamcs.xtce.FloatParameterType;
import org.yamcs.xtce.InputParameter;
import org.yamcs.xtce.MetaCommand;
import org.yamcs.xtce.OutputParameter;
import org.yamcs.xtce.Parameter;
import org.yamcs.xtce.SequenceContainer;
import org.yamcs.xtce.TimeEpoch;
import org.yamcs.xtce.XtceDb;

public class XlsV6LoaderTest {
    XtceDb db;
    
    @Before
    public void setupXtceDb() {
        YConfiguration.setupTest("refmdb");
        XtceDbFactory.reset();
        db = XtceDbFactory.getInstanceByConfig("refmdb", "refmdb-v6");
    }
    
    @Test
    public void testParameterAliases() throws Exception {
        Parameter p = db.getParameter("/REFMDB/SUBSYS1/IntegerPara1_1");
        assertNotNull(p);
        String aliasPathname = p.getAlias("MDB:Pathname");
        assertEquals("/ccsds-default/PKT1/IntegerPara1_1", aliasPathname);

        String aliasParam = p.getAlias("MDB:AliasParam");
        assertEquals("AliasParam1", aliasParam);

    }

    @Test
    public void testCommandAliases() throws Exception {
        MetaCommand cmd1 = db.getMetaCommand("/REFMDB/SUBSYS1/ONE_INT_ARG_TC");
        assertNotNull(cmd1);
        String alias = cmd1.getAlias("MDB:Alias1");
        assertEquals("AlternativeName1", alias);

        MetaCommand cmd2 = db.getMetaCommand("/REFMDB/SUBSYS1/FIXED_VALUE_TC");
        assertNotNull(cmd1);
        alias = cmd2.getAlias("MDB:Alias1");
        assertEquals("AlternativeName2", alias);

    }
    
    @Test
    public void testCommandVerifiers() throws Exception {
        MetaCommand cmd1 = db.getMetaCommand("/REFMDB/SUBSYS1/CONT_VERIF_TC");
        assertNotNull(cmd1);
        assertTrue(cmd1.hasCommandVerifiers());
        List<CommandVerifier> verifiers = cmd1.getCommandVerifiers();
        assertEquals(2, verifiers.size());
    }
    
    @Test
    public void testAlgorithmAliases() throws Exception {
        Algorithm algo = db.getAlgorithm("/REFMDB/SUBSYS1/sliding_window");
        assertNotNull(algo);
        String alias = algo.getAlias("namespace1");
        assertEquals("/alternative/name1", alias);
    
        algo = db.getAlgorithm("/REFMDB/SUBSYS1/float_ypr");
        assertNotNull(algo);
        alias = algo.getAlias("namespace1");
        assertEquals("another alternative name", alias);
    }
    
    @Test
    public void testContainerAliases() throws Exception {
        SequenceContainer container = db.getSequenceContainer("/REFMDB/SUBSYS1/PKT1_2");
        assertNotNull(container);
        String alias = container.getAlias("MDB:Pathname");
        assertEquals("REFMDB\\ACQ\\PKTS\\PKT12", alias);
    }
    
    @Test
    public void testReferenceAliases() throws Exception {
        Algorithm a = db.getAlgorithm("/REFMDB/SUBSYS1/algo_ext_spacesys");
        assertNotNull(a);
        List<InputParameter> lin =  a.getInputSet();
        assertEquals(1, lin.size());
        assertEquals("/REFMDB/col-packet_id", lin.get(0).getParameterInstance().getParameter().getQualifiedName());
        List<OutputParameter> lout = a.getOutputSet();
        assertEquals(1, lout.size());
        assertEquals("/REFMDB/algo_ext_spacesys_out", lout.get(0).getParameter().getQualifiedName());
    }
    
    @Test
    public void testTimeParam() throws Exception {
        Parameter p = db.getParameter("/REFMDB/SUBSYS1/TimePara6_1");
        assertEquals(TimeEpoch.CommonEpochs.GPS, ((AbsoluteTimeParameterType)p.getParameterType()).getReferenceTime().getEpoch().getCommonEpoch());
        
        p = db.getParameter("/REFMDB/SUBSYS1/TimePara6_2");
        assertEquals(0.0039062500, ((AbsoluteTimeParameterType)p.getParameterType()).getScale(), 1e-5);
    }
    
    
    @Test
    public void testContextCalib() throws Exception {
        Parameter p = db.getParameter("/REFMDB/SUBSYS1/FloatPara1_10_3");
        FloatParameterType ptype = (FloatParameterType) p.getParameterType();
        FloatDataEncoding encoding = (FloatDataEncoding) ptype.getEncoding();
        
        assertEquals(1, encoding.getContextCalibratorList().size());
    }
    
    
}
