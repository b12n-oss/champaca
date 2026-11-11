// Array access inside a method over codegen's ic-vector-limit (4000 bytecode
// bytes), where champaca calls runtime.ss's big-array-* procedures instead
// of inlining the array macros. table() is that method: a getContents-style
// table of 300 rows keeps it near 6000 bytes (javap -c shows its length),
// and every array instruction and failure runs inside it, so the values,
// the exceptions, their messages and the throwing frame's line come from
// the out-of-line path and are compared with HotSpot's. Accesses inside a
// loop (the last case) keep the inline macros.
public class BigMethodArrayCheck {
    static Object table(int mode, int[] ia, byte[] ba, char[] ca, short[] sa,
                        long[] la, float[] fa, double[] da, boolean[] za, Object[] oa) {
        Object[][] t = new Object[][] {
            {"key0", "value0"},
            {"key1", "value1"},
            {"key2", "value2"},
            {"key3", "value3"},
            {"key4", "value4"},
            {"key5", "value5"},
            {"key6", "value6"},
            {"key7", "value7"},
            {"key8", "value8"},
            {"key9", "value9"},
            {"key10", "value10"},
            {"key11", "value11"},
            {"key12", "value12"},
            {"key13", "value13"},
            {"key14", "value14"},
            {"key15", "value15"},
            {"key16", "value16"},
            {"key17", "value17"},
            {"key18", "value18"},
            {"key19", "value19"},
            {"key20", "value20"},
            {"key21", "value21"},
            {"key22", "value22"},
            {"key23", "value23"},
            {"key24", "value24"},
            {"key25", "value25"},
            {"key26", "value26"},
            {"key27", "value27"},
            {"key28", "value28"},
            {"key29", "value29"},
            {"key30", "value30"},
            {"key31", "value31"},
            {"key32", "value32"},
            {"key33", "value33"},
            {"key34", "value34"},
            {"key35", "value35"},
            {"key36", "value36"},
            {"key37", "value37"},
            {"key38", "value38"},
            {"key39", "value39"},
            {"key40", "value40"},
            {"key41", "value41"},
            {"key42", "value42"},
            {"key43", "value43"},
            {"key44", "value44"},
            {"key45", "value45"},
            {"key46", "value46"},
            {"key47", "value47"},
            {"key48", "value48"},
            {"key49", "value49"},
            {"key50", "value50"},
            {"key51", "value51"},
            {"key52", "value52"},
            {"key53", "value53"},
            {"key54", "value54"},
            {"key55", "value55"},
            {"key56", "value56"},
            {"key57", "value57"},
            {"key58", "value58"},
            {"key59", "value59"},
            {"key60", "value60"},
            {"key61", "value61"},
            {"key62", "value62"},
            {"key63", "value63"},
            {"key64", "value64"},
            {"key65", "value65"},
            {"key66", "value66"},
            {"key67", "value67"},
            {"key68", "value68"},
            {"key69", "value69"},
            {"key70", "value70"},
            {"key71", "value71"},
            {"key72", "value72"},
            {"key73", "value73"},
            {"key74", "value74"},
            {"key75", "value75"},
            {"key76", "value76"},
            {"key77", "value77"},
            {"key78", "value78"},
            {"key79", "value79"},
            {"key80", "value80"},
            {"key81", "value81"},
            {"key82", "value82"},
            {"key83", "value83"},
            {"key84", "value84"},
            {"key85", "value85"},
            {"key86", "value86"},
            {"key87", "value87"},
            {"key88", "value88"},
            {"key89", "value89"},
            {"key90", "value90"},
            {"key91", "value91"},
            {"key92", "value92"},
            {"key93", "value93"},
            {"key94", "value94"},
            {"key95", "value95"},
            {"key96", "value96"},
            {"key97", "value97"},
            {"key98", "value98"},
            {"key99", "value99"},
            {"key100", "value100"},
            {"key101", "value101"},
            {"key102", "value102"},
            {"key103", "value103"},
            {"key104", "value104"},
            {"key105", "value105"},
            {"key106", "value106"},
            {"key107", "value107"},
            {"key108", "value108"},
            {"key109", "value109"},
            {"key110", "value110"},
            {"key111", "value111"},
            {"key112", "value112"},
            {"key113", "value113"},
            {"key114", "value114"},
            {"key115", "value115"},
            {"key116", "value116"},
            {"key117", "value117"},
            {"key118", "value118"},
            {"key119", "value119"},
            {"key120", "value120"},
            {"key121", "value121"},
            {"key122", "value122"},
            {"key123", "value123"},
            {"key124", "value124"},
            {"key125", "value125"},
            {"key126", "value126"},
            {"key127", "value127"},
            {"key128", "value128"},
            {"key129", "value129"},
            {"key130", "value130"},
            {"key131", "value131"},
            {"key132", "value132"},
            {"key133", "value133"},
            {"key134", "value134"},
            {"key135", "value135"},
            {"key136", "value136"},
            {"key137", "value137"},
            {"key138", "value138"},
            {"key139", "value139"},
            {"key140", "value140"},
            {"key141", "value141"},
            {"key142", "value142"},
            {"key143", "value143"},
            {"key144", "value144"},
            {"key145", "value145"},
            {"key146", "value146"},
            {"key147", "value147"},
            {"key148", "value148"},
            {"key149", "value149"},
            {"key150", "value150"},
            {"key151", "value151"},
            {"key152", "value152"},
            {"key153", "value153"},
            {"key154", "value154"},
            {"key155", "value155"},
            {"key156", "value156"},
            {"key157", "value157"},
            {"key158", "value158"},
            {"key159", "value159"},
            {"key160", "value160"},
            {"key161", "value161"},
            {"key162", "value162"},
            {"key163", "value163"},
            {"key164", "value164"},
            {"key165", "value165"},
            {"key166", "value166"},
            {"key167", "value167"},
            {"key168", "value168"},
            {"key169", "value169"},
            {"key170", "value170"},
            {"key171", "value171"},
            {"key172", "value172"},
            {"key173", "value173"},
            {"key174", "value174"},
            {"key175", "value175"},
            {"key176", "value176"},
            {"key177", "value177"},
            {"key178", "value178"},
            {"key179", "value179"},
            {"key180", "value180"},
            {"key181", "value181"},
            {"key182", "value182"},
            {"key183", "value183"},
            {"key184", "value184"},
            {"key185", "value185"},
            {"key186", "value186"},
            {"key187", "value187"},
            {"key188", "value188"},
            {"key189", "value189"},
            {"key190", "value190"},
            {"key191", "value191"},
            {"key192", "value192"},
            {"key193", "value193"},
            {"key194", "value194"},
            {"key195", "value195"},
            {"key196", "value196"},
            {"key197", "value197"},
            {"key198", "value198"},
            {"key199", "value199"},
            {"key200", "value200"},
            {"key201", "value201"},
            {"key202", "value202"},
            {"key203", "value203"},
            {"key204", "value204"},
            {"key205", "value205"},
            {"key206", "value206"},
            {"key207", "value207"},
            {"key208", "value208"},
            {"key209", "value209"},
            {"key210", "value210"},
            {"key211", "value211"},
            {"key212", "value212"},
            {"key213", "value213"},
            {"key214", "value214"},
            {"key215", "value215"},
            {"key216", "value216"},
            {"key217", "value217"},
            {"key218", "value218"},
            {"key219", "value219"},
            {"key220", "value220"},
            {"key221", "value221"},
            {"key222", "value222"},
            {"key223", "value223"},
            {"key224", "value224"},
            {"key225", "value225"},
            {"key226", "value226"},
            {"key227", "value227"},
            {"key228", "value228"},
            {"key229", "value229"},
            {"key230", "value230"},
            {"key231", "value231"},
            {"key232", "value232"},
            {"key233", "value233"},
            {"key234", "value234"},
            {"key235", "value235"},
            {"key236", "value236"},
            {"key237", "value237"},
            {"key238", "value238"},
            {"key239", "value239"},
            {"key240", "value240"},
            {"key241", "value241"},
            {"key242", "value242"},
            {"key243", "value243"},
            {"key244", "value244"},
            {"key245", "value245"},
            {"key246", "value246"},
            {"key247", "value247"},
            {"key248", "value248"},
            {"key249", "value249"},
            {"key250", "value250"},
            {"key251", "value251"},
            {"key252", "value252"},
            {"key253", "value253"},
            {"key254", "value254"},
            {"key255", "value255"},
            {"key256", "value256"},
            {"key257", "value257"},
            {"key258", "value258"},
            {"key259", "value259"},
            {"key260", "value260"},
            {"key261", "value261"},
            {"key262", "value262"},
            {"key263", "value263"},
            {"key264", "value264"},
            {"key265", "value265"},
            {"key266", "value266"},
            {"key267", "value267"},
            {"key268", "value268"},
            {"key269", "value269"},
            {"key270", "value270"},
            {"key271", "value271"},
            {"key272", "value272"},
            {"key273", "value273"},
            {"key274", "value274"},
            {"key275", "value275"},
            {"key276", "value276"},
            {"key277", "value277"},
            {"key278", "value278"},
            {"key279", "value279"},
            {"key280", "value280"},
            {"key281", "value281"},
            {"key282", "value282"},
            {"key283", "value283"},
            {"key284", "value284"},
            {"key285", "value285"},
            {"key286", "value286"},
            {"key287", "value287"},
            {"key288", "value288"},
            {"key289", "value289"},
            {"key290", "value290"},
            {"key291", "value291"},
            {"key292", "value292"},
            {"key293", "value293"},
            {"key294", "value294"},
            {"key295", "value295"},
            {"key296", "value296"},
            {"key297", "value297"},
            {"key298", "value298"},
            {"key299", "value299"},
        };
        switch (mode) {
            case 0: {
                ia[1] = 7; ba[1] = (byte) 200; ca[1] = 'x'; sa[1] = (short) 70000;
                la[1] = 1L << 40; fa[1] = 1.5f; da[1] = 2.25; za[1] = true; oa[1] = "s";
                return t.length + " " + t[299][1] + " " + ia[1] + " " + ba[1] + " " + ca[1] + " " + sa[1]
                    + " " + la[1] + " " + fa[1] + " " + da[1] + " " + za[1] + " " + oa[1]
                    + " " + ia.length + " " + oa.length + " " + t[0].length;
            }
            case 1: return ia[ia.length];
            case 2: ia[-1] = 1; return null;
            case 3: return ba[0];
            case 4: ca[0] = 'c'; return null;
            case 5: sa[sa.length + 3] = 1; return null;
            case 6: return la[-5];
            case 7: da[0] = 1.0; return null;
            case 8: return oa[oa.length];
            case 9: oa[0] = Integer.valueOf(1); return null;
            case 10: oa[0] = "x"; return null;
            case 11: return ia.length;
            case 12: return t[300];
            case 13: return za[3];
            case 14: fa[9] = 1f; return null;
            case 16: return ia[0];
            case 17: la[1] = 2L; return null;
            case 18: return sa[2];
            case 19: return za[0];
            case 20: return oa[1];
            case 21: oa[-2] = "y"; return null;
            case 22: oa[3] = "z"; return null;
            case 23: oa[2] = null; return oa[2] == null ? "null stored" : "not stored";
            case 24: return fa[0];
            case 25: ia[0] = 4; return null;
            case 15: {
                // a loop: its accesses stay inline in a giant method
                long sum = 0;
                for (int k = 0; k <= ia.length; k++) { ia[k % 3] += k; sum += ia[k]; }
                return sum;
            }
            default: return null;
        }
    }

    static void run(String label, int mode, boolean nulls) {
        int[] ia = nulls ? null : new int[3];
        byte[] ba = nulls ? null : new byte[3];
        char[] ca = nulls ? null : new char[3];
        short[] sa = nulls ? null : new short[3];
        long[] la = nulls ? null : new long[3];
        float[] fa = nulls ? null : new float[3];
        double[] da = nulls ? null : new double[3];
        boolean[] za = nulls ? null : new boolean[3];
        Object[] oa = nulls ? null : new String[3];
        try {
            System.out.println(label + ": " + table(mode, ia, ba, ca, sa, la, fa, da, za, oa));
        } catch (RuntimeException e) {
            StackTraceElement top = e.getStackTrace()[0];
            System.out.println(label + ": " + e.getClass().getName() + ": " + e.getMessage()
                + " at " + top.getMethodName() + ":" + top.getLineNumber());
        }
    }

    public static void main(String[] args) {
        run("values", 0, false);
        run("iaload past the end", 1, false);
        run("iastore at -1", 2, false);
        run("baload on null", 3, true);
        run("castore on null", 4, true);
        run("sastore past the end", 5, false);
        run("laload at -5", 6, false);
        run("dastore on null", 7, true);
        run("aaload past the end", 8, false);
        run("aastore of the wrong type", 9, false);
        run("aastore on null", 10, true);
        run("arraylength of null", 11, true);
        run("aaload of the table past the end", 12, false);
        run("baload of a boolean array past the end", 13, false);
        run("fastore past the end", 14, false);
        run("a loop running past the end", 15, false);
        run("iaload on null", 16, true);
        run("lastore on null", 17, true);
        run("saload on null", 18, true);
        run("baload of a boolean array on null", 19, true);
        run("aaload on null", 20, true);
        run("aastore at -2", 21, false);
        run("aastore past the end", 22, false);
        run("aastore of null", 23, false);
        run("faload on null", 24, true);
        run("iastore on null", 25, true);
    }
}
