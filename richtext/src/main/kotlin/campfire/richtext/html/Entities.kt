package campfire.richtext.html

/**
 * The named character references html5ever's tokenizer knows (`web_atoms` 0.1.3's
 * `NAMED_ENTITIES`, which `once-campfire-rust/crates/richtext/vendor/html5ever` uses through
 * markup5ever 0.35), with every prefix of a name mapped to "no match yet", as html5ever's
 * generated map does. Names are stored without the leading `&`; each entry is `name=cp1[,cp2]`
 * with code points in base 36.
 */
internal object Entities {
    private const val DATA =
        "Aacute;=5d Aacute=5d aacute;=69 aacute=69 Abreve;=76 abreve;=77 ac;=6ri acd;=6rj acE;=6ri,mr Acirc;=" +
        "5e Acirc=5e acirc;=6a acirc=6a acute;=50 acute=50 Acy;=sw acy;=ts AElig;=5i AElig=5i aelig;=6e aelig" +
        "=6e af;=6e9 Afr;=2kn8 afr;=2kny Agrave;=5c Agrave=5c agrave;=68 agrave=68 alefsym;=6k5 aleph;=6k5 Al" +
        "pha;=pd alpha;=q9 Amacr;=74 amacr;=75 amalg;=8cf amp;=12 amp=12 AMP;=12 AMP=12 andand;=8d1 And;=8cz " +
        "and;=6qv andd;=8d8 andslope;=8d4 andv;=8d6 ang;=6qo ange;=884 angle;=6qo angmsdaa;=888 angmsdab;=889" +
        " angmsdac;=88a angmsdad;=88b angmsdae;=88c angmsdaf;=88d angmsdag;=88e angmsdah;=88f angmsd;=6qp ang" +
        "rt;=6qn angrtvb;=6v2 angrtvbd;=87x angsph;=6qq angst;=5h angzarr;=70c Aogon;=78 aogon;=79 Aopf;=2koo" +
        " aopf;=2kpe apacir;=8dr ap;=6rs apE;=8ds ape;=6ru apid;=6rv apos;=13 ApplyFunction;=6e9 approx;=6rs " +
        "approxeq;=6ru Aring;=5h Aring=5h aring;=6d aring=6d Ascr;=2kkc ascr;=2kl2 Assign;=6s4 ast;=16 asymp;" +
        "=6rs asympeq;=6rx Atilde;=5f Atilde=5f atilde;=6b atilde=6b Auml;=5g Auml=5g auml;=6c auml=6c awconi" +
        "nt;=6r7 awint;=8b5 backcong;=6rw backepsilon;=s6 backprime;=6d1 backsim;=6rh backsimeq;=6vh Backslas" +
        "h;=6qe Barv;=8h3 barvee;=6v1 barwed;=6x1 Barwed;=6x2 barwedge;=6x1 bbrk;=71x bbrktbrk;=71y bcong;=6r" +
        "w Bcy;=sx bcy;=tt bdquo;=6ce becaus;=6r9 because;=6r9 Because;=6r9 bemptyv;=88g bepsi;=s6 bernou;=6j" +
        "w Bernoullis;=6jw Beta;=pe beta;=qa beth;=6k6 between;=6ss Bfr;=2kn9 bfr;=2knz bigcap;=6v6 bigcirc;=" +
        "7hr bigcup;=6v7 bigodot;=8ao bigoplus;=8ap bigotimes;=8aq bigsqcup;=8au bigstar;=7id bigtriangledown" +
        ";=7gd bigtriangleup;=7g3 biguplus;=8as bigvee;=6v5 bigwedge;=6v4 bkarow;=83x blacklozenge;=8a3 black" +
        "square;=7fu blacktriangle;=7g4 blacktriangledown;=7ge blacktriangleleft;=7gi blacktriangleright;=7g8" +
        " blank;=74z blk12;=7f6 blk14;=7f5 blk34;=7f7 block;=7ew bne;=1p,6hx bnequiv;=6sh,6hx bNot;=8h9 bnot;" +
        "=6xc Bopf;=2kop bopf;=2kpf bot;=6ud bottom;=6ud bowtie;=6vc boxbox;=895 boxdl;=7bk boxdL;=7dh boxDl;" +
        "=7di boxDL;=7dj boxdr;=7bg boxdR;=7de boxDr;=7df boxDR;=7dg boxh;=7b4 boxH;=7dc boxhd;=7cc boxHd;=7d" +
        "w boxhD;=7dx boxHD;=7dy boxhu;=7ck boxHu;=7dz boxhU;=7e0 boxHU;=7e1 boxminus;=6u7 boxplus;=6u6 boxti" +
        "mes;=6u8 boxul;=7bs boxuL;=7dn boxUl;=7do boxUL;=7dp boxur;=7bo boxuR;=7dk boxUr;=7dl boxUR;=7dm box" +
        "v;=7b6 boxV;=7dd boxvh;=7cs boxvH;=7e2 boxVh;=7e3 boxVH;=7e4 boxvl;=7c4 boxvL;=7dt boxVl;=7du boxVL;" +
        "=7dv boxvr;=7bw boxvR;=7dq boxVr;=7dr boxVR;=7ds bprime;=6d1 breve;=k8 Breve;=k8 brvbar;=4m brvbar=4" +
        "m bscr;=2kl3 Bscr;=6jw bsemi;=6dr bsim;=6rh bsime;=6vh bsolb;=891 bsol;=2k bsolhsub;=7uw bull;=6ci b" +
        "ullet;=6ci bump;=6ry bumpE;=8fi bumpe;=6rz Bumpeq;=6ry bumpeq;=6rz Cacute;=7a cacute;=7b capand;=8ck" +
        " capbrcup;=8cp capcap;=8cr cap;=6qx Cap;=6vm capcup;=8cn capdot;=8cg CapitalDifferentialD;=6kl caps;" +
        "=6qx,1e68 caret;=6dd caron;=jr Cayleys;=6jx ccaps;=8ct Ccaron;=7g ccaron;=7h Ccedil;=5j Ccedil=5j cc" +
        "edil;=6f ccedil=6f Ccirc;=7c ccirc;=7d Cconint;=6r4 ccups;=8cs ccupssm;=8cw Cdot;=7e cdot;=7f cedil;" +
        "=54 cedil=54 Cedilla;=54 cemptyv;=88i cent;=4i cent=4i centerdot;=53 CenterDot;=53 cfr;=2ko0 Cfr;=6j" +
        "x CHcy;=tj chcy;=uf check;=7pv checkmark;=7pv Chi;=pz chi;=qv circ;=jq circeq;=6s7 circlearrowleft;=" +
        "6nu circlearrowright;=6nv circledast;=6u3 circledcirc;=6u2 circleddash;=6u5 CircleDot;=6u1 circledR;" +
        "=4u circledS;=79k CircleMinus;=6ty CirclePlus;=6tx CircleTimes;=6tz cir;=7gr cirE;=88z cire;=6s7 cir" +
        "fnint;=8b4 cirmid;=8hb cirscir;=88y ClockwiseContourIntegral;=6r6 CloseCurlyDoubleQuote;=6cd CloseCu" +
        "rlyQuote;=6c9 clubs;=7kz clubsuit;=7kz colon;=1m Colon;=6rb Colone;=8dw colone;=6s4 coloneq;=6s4 com" +
        "ma;=18 commat;=1s comp;=6pt compfn;=6qg complement;=6pt complexes;=6iq cong;=6rp congdot;=8dp Congru" +
        "ent;=6sh conint;=6r2 Conint;=6r3 ContourIntegral;=6r2 copf;=2kpg Copf;=6iq coprod;=6q8 Coproduct;=6q" +
        "8 copy;=4p copy=4p COPY;=4p COPY=4p copysr;=6jb CounterClockwiseContourIntegral;=6r7 crarr;=6np cros" +
        "s;=7pz Cross;=8bz Cscr;=2kke cscr;=2kl4 csub;=8gf csube;=8gh csup;=8gg csupe;=8gi ctdot;=6wf cudarrl" +
        ";=854 cudarrr;=851 cuepr;=6vy cuesc;=6vz cularr;=6nq cularrp;=859 cupbrcap;=8co cupcap;=8cm CupCap;=" +
        "6rx cup;=6qy Cup;=6vn cupcup;=8cq cupdot;=6tp cupor;=8cl cups;=6qy,1e68 curarr;=6nr curarrm;=858 cur" +
        "lyeqprec;=6vy curlyeqsucc;=6vz curlyvee;=6vi curlywedge;=6vj curren;=4k curren=4k curvearrowleft;=6n" +
        "q curvearrowright;=6nr cuvee;=6vi cuwed;=6vj cwconint;=6r6 cwint;=6r5 cylcty;=6y5 dagger;=6cg Dagger" +
        ";=6ch daleth;=6k8 darr;=6mr Darr;=6n5 dArr;=6oj dash;=6c0 Dashv;=8h0 dashv;=6ub dbkarow;=83z dblac;=" +
        "kd Dcaron;=7i dcaron;=7j Dcy;=t0 dcy;=tw ddagger;=6ch ddarr;=6oa DD;=6kl dd;=6km DDotrahd;=841 ddots" +
        "eq;=8dz deg;=4w deg=4w Del;=6pz Delta;=pg delta;=qc demptyv;=88h dfisht;=873 Dfr;=2knb dfr;=2ko1 dHa" +
        "r;=86d dharl;=6o3 dharr;=6o2 DiacriticalAcute;=50 DiacriticalDot;=k9 DiacriticalDoubleAcute;=kd Diac" +
        "riticalGrave;=2o DiacriticalTilde;=kc diam;=6v8 diamond;=6v8 Diamond;=6v8 diamondsuit;=7l2 diams;=7l" +
        "2 die;=4o DifferentialD;=6km digamma;=rh disin;=6wi div;=6v divide;=6v divide=6v divideontimes;=6vb " +
        "divonx;=6vb DJcy;=si djcy;=uq dlcorn;=6xq dlcrop;=6x9 dollar;=10 Dopf;=2kor dopf;=2kph Dot;=4o dot;=" +
        "k9 DotDot;=6ho doteq;=6s0 doteqdot;=6s1 DotEqual;=6s0 dotminus;=6rc dotplus;=6qc dotsquare;=6u9 doub" +
        "lebarwedge;=6x2 DoubleContourIntegral;=6r3 DoubleDot;=4o DoubleDownArrow;=6oj DoubleLeftArrow;=6og D" +
        "oubleLeftRightArrow;=6ok DoubleLeftTee;=8h0 DoubleLongLeftArrow;=7w8 DoubleLongLeftRightArrow;=7wa D" +
        "oubleLongRightArrow;=7w9 DoubleRightArrow;=6oi DoubleRightTee;=6ug DoubleUpArrow;=6oh DoubleUpDownAr" +
        "row;=6ol DoubleVerticalBar;=6qt DownArrowBar;=843 downarrow;=6mr DownArrow;=6mr Downarrow;=6oj DownA" +
        "rrowUpArrow;=6ph DownBreve;=lt downdownarrows;=6oa downharpoonleft;=6o3 downharpoonright;=6o2 DownLe" +
        "ftRightVector;=85s DownLeftTeeVector;=866 DownLeftVectorBar;=85y DownLeftVector;=6nx DownRightTeeVec" +
        "tor;=867 DownRightVectorBar;=85z DownRightVector;=6o1 DownTeeArrow;=6nb DownTee;=6uc drbkarow;=840 d" +
        "rcorn;=6xr drcrop;=6x8 Dscr;=2kkf dscr;=2kl5 DScy;=sl dscy;=ut dsol;=8ae Dstrok;=7k dstrok;=7l dtdot" +
        ";=6wh dtri;=7gf dtrif;=7ge duarr;=6ph duhar;=86n dwangle;=886 DZcy;=sv dzcy;=v3 dzigrarr;=7wf Eacute" +
        ";=5l Eacute=5l eacute;=6h eacute=6h easter;=8dq Ecaron;=7u ecaron;=7v Ecirc;=5m Ecirc=5m ecirc;=6i e" +
        "circ=6i ecir;=6s6 ecolon;=6s5 Ecy;=tp ecy;=ul eDDot;=8dz Edot;=7q edot;=7r eDot;=6s1 ee;=6kn efDot;=" +
        "6s2 Efr;=2knc efr;=2ko2 eg;=8ey Egrave;=5k Egrave=5k egrave;=6g egrave=6g egs;=8eu egsdot;=8ew el;=8" +
        "ex Element;=6q0 elinters;=73b ell;=6j7 els;=8et elsdot;=8ev Emacr;=7m emacr;=7n empty;=6px emptyset;" +
        "=6px EmptySmallSquare;=7i3 emptyv;=6px EmptyVerySmallSquare;=7fv emsp13;=6bo emsp14;=6bp emsp;=6bn E" +
        "NG;=96 eng;=97 ensp;=6bm Eogon;=7s eogon;=7t Eopf;=2kos eopf;=2kpi epar;=6vp eparsl;=89v eplus;=8dt " +
        "epsi;=qd Epsilon;=ph epsilon;=qd epsiv;=s5 eqcirc;=6s6 eqcolon;=6s5 eqsim;=6rm eqslantgtr;=8eu eqsla" +
        "ntless;=8et Equal;=8dx equals;=1p EqualTilde;=6rm equest;=6sf Equilibrium;=6oc equiv;=6sh equivDD;=8" +
        "e0 eqvparsl;=89x erarr;=86p erDot;=6s3 escr;=6jz Escr;=6k0 esdot;=6s0 Esim;=8dv esim;=6rm Eta;=pj et" +
        "a;=qf ETH;=5s ETH=5s eth;=6o eth=6o Euml;=5n Euml=5n euml;=6j euml=6j euro;=6gc excl;=x exist;=6pv E" +
        "xists;=6pv expectation;=6k0 exponentiale;=6kn ExponentialE;=6kn fallingdotseq;=6s2 Fcy;=tg fcy;=uc f" +
        "emale;=7k0 ffilig;=1dkz fflig;=1dkw ffllig;=1dl0 Ffr;=2knd ffr;=2ko3 filig;=1dkx FilledSmallSquare;=" +
        "7i4 FilledVerySmallSquare;=7fu fjlig;=2u,2y flat;=7l9 fllig;=1dky fltns;=7g1 fnof;=b6 Fopf;=2kot fop" +
        "f;=2kpj forall;=6ps ForAll;=6ps fork;=6vo forkv;=8gp Fouriertrf;=6k1 fpartint;=8b1 frac12;=59 frac12" +
        "=59 frac13;=6kz frac14;=58 frac14=58 frac15;=6l1 frac16;=6l5 frac18;=6l7 frac23;=6l0 frac25;=6l2 fra" +
        "c34;=5a frac34=5a frac35;=6l3 frac38;=6l8 frac45;=6l4 frac56;=6l6 frac58;=6l9 frac78;=6la frasl;=6dg" +
        " frown;=6xu fscr;=2kl7 Fscr;=6k1 gacute;=dx Gamma;=pf gamma;=qb Gammad;=rg gammad;=rh gap;=8ee Gbrev" +
        "e;=7y gbreve;=7z Gcedil;=82 Gcirc;=7w gcirc;=7x Gcy;=sz gcy;=tv Gdot;=80 gdot;=81 ge;=6sl gE;=6sn gE" +
        "l;=8ek gel;=6vv geq;=6sl geqq;=6sn geqslant;=8e6 gescc;=8fd ges;=8e6 gesdot;=8e8 gesdoto;=8ea gesdot" +
        "ol;=8ec gesl;=6vv,1e68 gesles;=8es Gfr;=2kne gfr;=2ko4 gg;=6sr Gg;=6vt ggg;=6vt gimel;=6k7 GJcy;=sj " +
        "gjcy;=ur gla;=8f9 gl;=6t3 glE;=8eq glj;=8f8 gnap;=8ei gnapprox;=8ei gne;=8eg gnE;=6sp gneq;=8eg gneq" +
        "q;=6sp gnsim;=6w7 Gopf;=2kou gopf;=2kpk grave;=2o GreaterEqual;=6sl GreaterEqualLess;=6vv GreaterFul" +
        "lEqual;=6sn GreaterGreater;=8f6 GreaterLess;=6t3 GreaterSlantEqual;=8e6 GreaterTilde;=6sz Gscr;=2kki" +
        " gscr;=6iy gsim;=6sz gsime;=8em gsiml;=8eo gtcc;=8fb gtcir;=8e2 gt;=1q gt=1q GT;=1q GT=1q Gt;=6sr gt" +
        "dot;=6vr gtlPar;=87p gtquest;=8e4 gtrapprox;=8ee gtrarr;=86w gtrdot;=6vr gtreqless;=6vv gtreqqless;=" +
        "8ek gtrless;=6t3 gtrsim;=6sz gvertneqq;=6sp,1e68 gvnE;=6sp,1e68 Hacek;=jr hairsp;=6bu half;=59 hamil" +
        "t;=6iz HARDcy;=tm hardcy;=ui harrcir;=85k harr;=6ms hArr;=6ok harrw;=6nh Hat;=2m hbar;=6j3 Hcirc;=84" +
        " hcirc;=85 hearts;=7l1 heartsuit;=7l1 hellip;=6cm hercon;=6ux hfr;=2ko5 Hfr;=6j0 HilbertSpace;=6iz h" +
        "ksearow;=84l hkswarow;=84m hoarr;=6pr homtht;=6rf hookleftarrow;=6nd hookrightarrow;=6ne hopf;=2kpl " +
        "Hopf;=6j1 horbar;=6c5 HorizontalLine;=7b4 hscr;=2kl9 Hscr;=6iz hslash;=6j3 Hstrok;=86 hstrok;=87 Hum" +
        "pDownHump;=6ry HumpEqual;=6rz hybull;=6df hyphen;=6c0 Iacute;=5p Iacute=5p iacute;=6l iacute=6l ic;=" +
        "6eb Icirc;=5q Icirc=5q icirc;=6m icirc=6m Icy;=t4 icy;=u0 Idot;=8g IEcy;=t1 iecy;=tx iexcl;=4h iexcl" +
        "=4h iff;=6ok ifr;=2ko6 Ifr;=6j5 Igrave;=5o Igrave=5o igrave;=6k igrave=6k ii;=6ko iiiint;=8b0 iiint;" +
        "=6r1 iinfin;=89o iiota;=6jt IJlig;=8i ijlig;=8j Imacr;=8a imacr;=8b image;=6j5 ImaginaryI;=6ko imagl" +
        "ine;=6j4 imagpart;=6j5 imath;=8h Im;=6j5 imof;=6uv imped;=c5 Implies;=6oi incare;=6it in;=6q0 infin;" +
        "=6qm infintie;=89p inodot;=8h intcal;=6uy int;=6qz Int;=6r0 integers;=6jo Integral;=6qz intercal;=6u" +
        "y Intersection;=6v6 intlarhk;=8bb intprod;=8cc InvisibleComma;=6eb InvisibleTimes;=6ea IOcy;=sh iocy" +
        ";=up Iogon;=8e iogon;=8f Iopf;=2kow iopf;=2kpm Iota;=pl iota;=qh iprod;=8cc iquest;=5b iquest=5b isc" +
        "r;=2kla Iscr;=6j4 isin;=6q0 isindot;=6wl isinE;=6wp isins;=6wk isinsv;=6wj isinv;=6q0 it;=6ea Itilde" +
        ";=88 itilde;=89 Iukcy;=sm iukcy;=uu Iuml;=5r Iuml=5r iuml;=6n iuml=6n Jcirc;=8k jcirc;=8l Jcy;=t5 jc" +
        "y;=u1 Jfr;=2knh jfr;=2ko7 jmath;=fr Jopf;=2kox jopf;=2kpn Jscr;=2kkl jscr;=2klb Jsercy;=so jsercy;=u" +
        "w Jukcy;=sk jukcy;=us Kappa;=pm kappa;=qi kappav;=s0 Kcedil;=8m kcedil;=8n Kcy;=t6 kcy;=u2 Kfr;=2kni" +
        " kfr;=2ko8 kgreen;=8o KHcy;=th khcy;=ud KJcy;=ss kjcy;=v0 Kopf;=2koy kopf;=2kpo Kscr;=2kkm kscr;=2kl" +
        "c lAarr;=6oq Lacute;=8p lacute;=8q laemptyv;=88k lagran;=6j6 Lambda;=pn lambda;=qj lang;=7vs Lang;=7" +
        "vu langd;=87l langle;=7vs lap;=8ed Laplacetrf;=6j6 laquo;=4r laquo=4r larrb;=6p0 larrbfs;=84f larr;=" +
        "6mo Larr;=6n2 lArr;=6og larrfs;=84d larrhk;=6nd larrlp;=6nf larrpl;=855 larrsim;=86r larrtl;=6n6 lat" +
        "ail;=849 lAtail;=84b lat;=8ff late;=8fh lates;=8fh,1e68 lbarr;=83w lBarr;=83y lbbrk;=7si lbrace;=3f " +
        "lbrack;=2j lbrke;=87f lbrksld;=87j lbrkslu;=87h Lcaron;=8t lcaron;=8u Lcedil;=8r lcedil;=8s lceil;=6" +
        "x4 lcub;=3f Lcy;=t7 lcy;=u3 ldca;=852 ldquo;=6cc ldquor;=6ce ldrdhar;=86f ldrushar;=85n ldsh;=6nm le" +
        ";=6sk lE;=6sm LeftAngleBracket;=7vs LeftArrowBar;=6p0 leftarrow;=6mo LeftArrow;=6mo Leftarrow;=6og L" +
        "eftArrowRightArrow;=6o6 leftarrowtail;=6n6 LeftCeiling;=6x4 LeftDoubleBracket;=7vq LeftDownTeeVector" +
        ";=869 LeftDownVectorBar;=861 LeftDownVector;=6o3 LeftFloor;=6x6 leftharpoondown;=6nx leftharpoonup;=" +
        "6nw leftleftarrows;=6o7 leftrightarrow;=6ms LeftRightArrow;=6ms Leftrightarrow;=6ok leftrightarrows;" +
        "=6o6 leftrightharpoons;=6ob leftrightsquigarrow;=6nh LeftRightVector;=85q LeftTeeArrow;=6n8 LeftTee;" +
        "=6ub LeftTeeVector;=862 leftthreetimes;=6vf LeftTriangleBar;=89b LeftTriangle;=6uq LeftTriangleEqual" +
        ";=6us LeftUpDownVector;=85t LeftUpTeeVector;=868 LeftUpVectorBar;=860 LeftUpVector;=6nz LeftVectorBa" +
        "r;=85u LeftVector;=6nw lEg;=8ej leg;=6vu leq;=6sk leqq;=6sm leqslant;=8e5 lescc;=8fc les;=8e5 lesdot" +
        ";=8e7 lesdoto;=8e9 lesdotor;=8eb lesg;=6vu,1e68 lesges;=8er lessapprox;=8ed lessdot;=6vq lesseqgtr;=" +
        "6vu lesseqqgtr;=8ej LessEqualGreater;=6vu LessFullEqual;=6sm LessGreater;=6t2 lessgtr;=6t2 LessLess;" +
        "=8f5 lesssim;=6sy LessSlantEqual;=8e5 LessTilde;=6sy lfisht;=870 lfloor;=6x6 Lfr;=2knj lfr;=2ko9 lg;" +
        "=6t2 lgE;=8ep lHar;=86a lhard;=6nx lharu;=6nw lharul;=86i lhblk;=7es LJcy;=sp ljcy;=ux llarr;=6o7 ll" +
        ";=6sq Ll;=6vs llcorner;=6xq Lleftarrow;=6oq llhard;=86j lltri;=7i2 Lmidot;=8v lmidot;=8w lmoustache;" +
        "=71s lmoust;=71s lnap;=8eh lnapprox;=8eh lne;=8ef lnE;=6so lneq;=8ef lneqq;=6so lnsim;=6w6 loang;=7v" +
        "w loarr;=6pp lobrk;=7vq longleftarrow;=7w5 LongLeftArrow;=7w5 Longleftarrow;=7w8 longleftrightarrow;" +
        "=7w7 LongLeftRightArrow;=7w7 Longleftrightarrow;=7wa longmapsto;=7wc longrightarrow;=7w6 LongRightAr" +
        "row;=7w6 Longrightarrow;=7w9 looparrowleft;=6nf looparrowright;=6ng lopar;=879 Lopf;=2koz lopf;=2kpp" +
        " loplus;=8bx lotimes;=8c4 lowast;=6qf lowbar;=2n LowerLeftArrow;=6mx LowerRightArrow;=6mw loz;=7gq l" +
        "ozenge;=7gq lozf;=8a3 lpar;=14 lparlt;=87n lrarr;=6o6 lrcorner;=6xr lrhar;=6ob lrhard;=86l lrm;=6by " +
        "lrtri;=6v3 lsaquo;=6d5 lscr;=2kld Lscr;=6j6 lsh;=6nk Lsh;=6nk lsim;=6sy lsime;=8el lsimg;=8en lsqb;=" +
        "2j lsquo;=6c8 lsquor;=6ca Lstrok;=8x lstrok;=8y ltcc;=8fa ltcir;=8e1 lt;=1o lt=1o LT;=1o LT=1o Lt;=6" +
        "sq ltdot;=6vq lthree;=6vf ltimes;=6vd ltlarr;=86u ltquest;=8e3 ltri;=7gj ltrie;=6us ltrif;=7gi ltrPa" +
        "r;=87q lurdshar;=85m luruhar;=86e lvertneqq;=6so,1e68 lvnE;=6so,1e68 macr;=4v macr=4v male;=7k2 malt" +
        ";=7q8 maltese;=7q8 Map;=83p map;=6na mapsto;=6na mapstodown;=6nb mapstoleft;=6n8 mapstoup;=6n9 marke" +
        "r;=7fy mcomma;=8bt Mcy;=t8 mcy;=u4 mdash;=6c4 mDDot;=6re measuredangle;=6qp MediumSpace;=6e7 Mellint" +
        "rf;=6k3 Mfr;=2knk mfr;=2koa mho;=6jr micro;=51 micro=51 midast;=16 midcir;=8hc mid;=6qr middot;=53 m" +
        "iddot=53 minusb;=6u7 minus;=6qa minusd;=6rc minusdu;=8bu MinusPlus;=6qb mlcp;=8gr mldr;=6cm mnplus;=" +
        "6qb models;=6uf Mopf;=2kp0 mopf;=2kpq mp;=6qb mscr;=2kle Mscr;=6k3 mstpos;=6ri Mu;=po mu;=qk multima" +
        "p;=6uw mumap;=6uw nabla;=6pz Nacute;=8z nacute;=90 nang;=6qo,6he nap;=6rt napE;=8ds,mw napid;=6rv,mw" +
        " napos;=95 napprox;=6rt natural;=7la naturals;=6j9 natur;=7la nbsp;=4g nbsp=4g nbump;=6ry,mw nbumpe;" +
        "=6rz,mw ncap;=8cj Ncaron;=93 ncaron;=94 Ncedil;=91 ncedil;=92 ncong;=6rr ncongdot;=8dp,mw ncup;=8ci " +
        "Ncy;=t9 ncy;=u5 ndash;=6c3 nearhk;=84k nearr;=6mv neArr;=6on nearrow;=6mv ne;=6sg nedot;=6s0,mw Nega" +
        "tiveMediumSpace;=6bv NegativeThickSpace;=6bv NegativeThinSpace;=6bv NegativeVeryThinSpace;=6bv nequi" +
        "v;=6si nesear;=84o nesim;=6rm,mw NestedGreaterGreater;=6sr NestedLessLess;=6sq NewLine;=a nexist;=6p" +
        "w nexists;=6pw Nfr;=2knl nfr;=2kob ngE;=6sn,mw nge;=6sx ngeq;=6sx ngeqq;=6sn,mw ngeqslant;=8e6,mw ng" +
        "es;=8e6,mw nGg;=6vt,mw ngsim;=6t1 nGt;=6sr,6he ngt;=6sv ngtr;=6sv nGtv;=6sr,mw nharr;=6ni nhArr;=6oe" +
        " nhpar;=8he ni;=6q3 nis;=6ws nisd;=6wq niv;=6q3 NJcy;=sq njcy;=uy nlarr;=6my nlArr;=6od nldr;=6cl nl" +
        "E;=6sm,mw nle;=6sw nleftarrow;=6my nLeftarrow;=6od nleftrightarrow;=6ni nLeftrightarrow;=6oe nleq;=6" +
        "sw nleqq;=6sm,mw nleqslant;=8e5,mw nles;=8e5,mw nless;=6su nLl;=6vs,mw nlsim;=6t0 nLt;=6sq,6he nlt;=" +
        "6su nltri;=6wa nltrie;=6wc nLtv;=6sq,mw nmid;=6qs NoBreak;=6e8 NonBreakingSpace;=4g nopf;=2kpr Nopf;" +
        "=6j9 Not;=8h8 not;=4s not=4s NotCongruent;=6si NotCupCap;=6st NotDoubleVerticalBar;=6qu NotElement;=" +
        "6q1 NotEqual;=6sg NotEqualTilde;=6rm,mw NotExists;=6pw NotGreater;=6sv NotGreaterEqual;=6sx NotGreat" +
        "erFullEqual;=6sn,mw NotGreaterGreater;=6sr,mw NotGreaterLess;=6t5 NotGreaterSlantEqual;=8e6,mw NotGr" +
        "eaterTilde;=6t1 NotHumpDownHump;=6ry,mw NotHumpEqual;=6rz,mw notin;=6q1 notindot;=6wl,mw notinE;=6wp" +
        ",mw notinva;=6q1 notinvb;=6wn notinvc;=6wm NotLeftTriangleBar;=89b,mw NotLeftTriangle;=6wa NotLeftTr" +
        "iangleEqual;=6wc NotLess;=6su NotLessEqual;=6sw NotLessGreater;=6t4 NotLessLess;=6sq,mw NotLessSlant" +
        "Equal;=8e5,mw NotLessTilde;=6t0 NotNestedGreaterGreater;=8f6,mw NotNestedLessLess;=8f5,mw notni;=6q4" +
        " notniva;=6q4 notnivb;=6wu notnivc;=6wt NotPrecedes;=6tc NotPrecedesEqual;=8fj,mw NotPrecedesSlantEq" +
        "ual;=6w0 NotReverseElement;=6q4 NotRightTriangleBar;=89c,mw NotRightTriangle;=6wb NotRightTriangleEq" +
        "ual;=6wd NotSquareSubset;=6tr,mw NotSquareSubsetEqual;=6w2 NotSquareSuperset;=6ts,mw NotSquareSupers" +
        "etEqual;=6w3 NotSubset;=6te,6he NotSubsetEqual;=6tk NotSucceeds;=6td NotSucceedsEqual;=8fk,mw NotSuc" +
        "ceedsSlantEqual;=6w1 NotSucceedsTilde;=6tb,mw NotSuperset;=6tf,6he NotSupersetEqual;=6tl NotTilde;=6" +
        "rl NotTildeEqual;=6ro NotTildeFullEqual;=6rr NotTildeTilde;=6rt NotVerticalBar;=6qs nparallel;=6qu n" +
        "par;=6qu nparsl;=8hp,6hx npart;=6pu,mw npolint;=8b8 npr;=6tc nprcue;=6w0 nprec;=6tc npreceq;=8fj,mw " +
        "npre;=8fj,mw nrarrc;=84z,mw nrarr;=6mz nrArr;=6of nrarrw;=6n1,mw nrightarrow;=6mz nRightarrow;=6of n" +
        "rtri;=6wb nrtrie;=6wd nsc;=6td nsccue;=6w1 nsce;=8fk,mw Nscr;=2kkp nscr;=2klf nshortmid;=6qs nshortp" +
        "arallel;=6qu nsim;=6rl nsime;=6ro nsimeq;=6ro nsmid;=6qs nspar;=6qu nsqsube;=6w2 nsqsupe;=6w3 nsub;=" +
        "6tg nsubE;=8g5,mw nsube;=6tk nsubset;=6te,6he nsubseteq;=6tk nsubseteqq;=8g5,mw nsucc;=6td nsucceq;=" +
        "8fk,mw nsup;=6th nsupE;=8g6,mw nsupe;=6tl nsupset;=6tf,6he nsupseteq;=6tl nsupseteqq;=8g6,mw ntgl;=6" +
        "t5 Ntilde;=5t Ntilde=5t ntilde;=6p ntilde=6p ntlg;=6t4 ntriangleleft;=6wa ntrianglelefteq;=6wc ntria" +
        "ngleright;=6wb ntrianglerighteq;=6wd Nu;=pp nu;=ql num;=z numero;=6ja numsp;=6br nvap;=6rx,6he nvdas" +
        "h;=6uk nvDash;=6ul nVdash;=6um nVDash;=6un nvge;=6sl,6he nvgt;=1q,6he nvHarr;=83o nvinfin;=89q nvlAr" +
        "r;=83m nvle;=6sk,6he nvlt;=1o,6he nvltrie;=6us,6he nvrArr;=83n nvrtrie;=6ut,6he nvsim;=6rg,6he nwarh" +
        "k;=84j nwarr;=6mu nwArr;=6om nwarrow;=6mu nwnear;=84n Oacute;=5v Oacute=5v oacute;=6r oacute=6r oast" +
        ";=6u3 Ocirc;=5w Ocirc=5w ocirc;=6s ocirc=6s ocir;=6u2 Ocy;=ta ocy;=u6 odash;=6u5 Odblac;=9c odblac;=" +
        "9d odiv;=8c8 odot;=6u1 odsold;=88s OElig;=9e oelig;=9f ofcir;=88v Ofr;=2knm ofr;=2koc ogon;=kb Ograv" +
        "e;=5u Ograve=5u ograve;=6q ograve=6q ogt;=88x ohbar;=88l ohm;=q1 oint;=6r2 olarr;=6nu olcir;=88u olc" +
        "ross;=88r oline;=6da olt;=88w Omacr;=98 omacr;=99 Omega;=q1 omega;=qx Omicron;=pr omicron;=qn omid;=" +
        "88m ominus;=6ty Oopf;=2kp2 oopf;=2kps opar;=88n OpenCurlyDoubleQuote;=6cc OpenCurlyQuote;=6c8 operp;" +
        "=88p oplus;=6tx orarr;=6nv Or;=8d0 or;=6qw ord;=8d9 order;=6k4 orderof;=6k4 ordf;=4q ordf=4q ordm;=5" +
        "6 ordm=56 origof;=6uu oror;=8d2 orslope;=8d3 orv;=8d7 oS;=79k Oscr;=2kkq oscr;=6k4 Oslash;=60 Oslash" +
        "=60 oslash;=6w oslash=6w osol;=6u0 Otilde;=5x Otilde=5x otilde;=6t otilde=6t otimesas;=8c6 Otimes;=8" +
        "c7 otimes;=6tz Ouml;=5y Ouml=5y ouml;=6u ouml=6u ovbar;=6yl OverBar;=6da OverBrace;=732 OverBracket;" +
        "=71w OverParenthesis;=730 para;=52 para=52 parallel;=6qt par;=6qt parsim;=8hf parsl;=8hp part;=6pu P" +
        "artialD;=6pu Pcy;=tb pcy;=u7 percnt;=11 period;=1a permil;=6cw perp;=6ud pertenk;=6cx Pfr;=2knn pfr;" +
        "=2kod Phi;=py phi;=qu phiv;=r9 phmmat;=6k3 phone;=7im Pi;=ps pi;=qo pitchfork;=6vo piv;=ra planck;=6" +
        "j3 planckh;=6j2 plankv;=6j3 plusacir;=8bn plusb;=6u6 pluscir;=8bm plus;=17 plusdo;=6qc plusdu;=8bp p" +
        "luse;=8du PlusMinus;=4x plusmn;=4x plusmn=4x plussim;=8bq plustwo;=8br pm;=4x Poincareplane;=6j0 poi" +
        "ntint;=8b9 popf;=2kpt Popf;=6jd pound;=4j pound=4j prap;=8fr Pr;=8fv pr;=6t6 prcue;=6t8 precapprox;=" +
        "8fr prec;=6t6 preccurlyeq;=6t8 Precedes;=6t6 PrecedesEqual;=8fj PrecedesSlantEqual;=6t8 PrecedesTild" +
        "e;=6ta preceq;=8fj precnapprox;=8ft precneqq;=8fp precnsim;=6w8 pre;=8fj prE;=8fn precsim;=6ta prime" +
        ";=6cy Prime;=6cz primes;=6jd prnap;=8ft prnE;=8fp prnsim;=6w8 prod;=6q7 Product;=6q7 profalar;=6y6 p" +
        "rofline;=6xe profsurf;=6xf prop;=6ql Proportional;=6ql Proportion;=6rb propto;=6ql prsim;=6ta prurel" +
        ";=6uo Pscr;=2kkr pscr;=2klh Psi;=q0 psi;=qw puncsp;=6bs Qfr;=2kno qfr;=2koe qint;=8b0 qopf;=2kpu Qop" +
        "f;=6je qprime;=6dz Qscr;=2kks qscr;=2kli quaternions;=6j1 quatint;=8ba quest;=1r questeq;=6sf quot;=" +
        "y quot=y QUOT;=y QUOT=y rAarr;=6or race;=6rh,mp Racute;=9g racute;=9h radic;=6qi raemptyv;=88j rang;" +
        "=7vt Rang;=7vv rangd;=87m range;=885 rangle;=7vt raquo;=57 raquo=57 rarrap;=86t rarrb;=6p1 rarrbfs;=" +
        "84g rarrc;=84z rarr;=6mq Rarr;=6n4 rArr;=6oi rarrfs;=84e rarrhk;=6ne rarrlp;=6ng rarrpl;=85h rarrsim" +
        ";=86s Rarrtl;=846 rarrtl;=6n7 rarrw;=6n1 ratail;=84a rAtail;=84c ratio;=6ra rationals;=6je rbarr;=83" +
        "x rBarr;=83z RBarr;=840 rbbrk;=7sj rbrace;=3h rbrack;=2l rbrke;=87g rbrksld;=87i rbrkslu;=87k Rcaron" +
        ";=9k rcaron;=9l Rcedil;=9i rcedil;=9j rceil;=6x5 rcub;=3h Rcy;=tc rcy;=u8 rdca;=853 rdldhar;=86h rdq" +
        "uo;=6cd rdquor;=6cd rdsh;=6nn real;=6jg realine;=6jf realpart;=6jg reals;=6jh Re;=6jg rect;=7fx reg;" +
        "=4u reg=4u REG;=4u REG=4u ReverseElement;=6q3 ReverseEquilibrium;=6ob ReverseUpEquilibrium;=86n rfis" +
        "ht;=871 rfloor;=6x7 rfr;=2kof Rfr;=6jg rHar;=86c rhard;=6o1 rharu;=6o0 rharul;=86k Rho;=pt rho;=qp r" +
        "hov;=s1 RightAngleBracket;=7vt RightArrowBar;=6p1 rightarrow;=6mq RightArrow;=6mq Rightarrow;=6oi Ri" +
        "ghtArrowLeftArrow;=6o4 rightarrowtail;=6n7 RightCeiling;=6x5 RightDoubleBracket;=7vr RightDownTeeVec" +
        "tor;=865 RightDownVectorBar;=85x RightDownVector;=6o2 RightFloor;=6x7 rightharpoondown;=6o1 righthar" +
        "poonup;=6o0 rightleftarrows;=6o4 rightleftharpoons;=6oc rightrightarrows;=6o9 rightsquigarrow;=6n1 R" +
        "ightTeeArrow;=6na RightTee;=6ua RightTeeVector;=863 rightthreetimes;=6vg RightTriangleBar;=89c Right" +
        "Triangle;=6ur RightTriangleEqual;=6ut RightUpDownVector;=85r RightUpTeeVector;=864 RightUpVectorBar;" +
        "=85w RightUpVector;=6ny RightVectorBar;=85v RightVector;=6o0 ring;=ka risingdotseq;=6s3 rlarr;=6o4 r" +
        "lhar;=6oc rlm;=6bz rmoustache;=71t rmoust;=71t rnmid;=8ha roang;=7vx roarr;=6pq robrk;=7vr ropar;=87" +
        "a ropf;=2kpv Ropf;=6jh roplus;=8by rotimes;=8c5 RoundImplies;=86o rpar;=15 rpargt;=87o rppolint;=8b6" +
        " rrarr;=6o9 Rrightarrow;=6or rsaquo;=6d6 rscr;=2klj Rscr;=6jf rsh;=6nl Rsh;=6nl rsqb;=2l rsquo;=6c9 " +
        "rsquor;=6c9 rthree;=6vg rtimes;=6ve rtri;=7g9 rtrie;=6ut rtrif;=7g8 rtriltri;=89a RuleDelayed;=8ac r" +
        "uluhar;=86g rx;=6ji Sacute;=9m sacute;=9n sbquo;=6ca scap;=8fs Scaron;=9s scaron;=9t Sc;=8fw sc;=6t7" +
        " sccue;=6t9 sce;=8fk scE;=8fo Scedil;=9q scedil;=9r Scirc;=9o scirc;=9p scnap;=8fu scnE;=8fq scnsim;" +
        "=6w9 scpolint;=8b7 scsim;=6tb Scy;=td scy;=u9 sdotb;=6u9 sdot;=6v9 sdote;=8di searhk;=84l searr;=6mw" +
        " seArr;=6oo searrow;=6mw sect;=4n sect=4n semi;=1n seswar;=84p setminus;=6qe setmn;=6qe sext;=7qu Sf" +
        "r;=2knq sfr;=2kog sfrown;=6xu sharp;=7lb SHCHcy;=tl shchcy;=uh SHcy;=tk shcy;=ug ShortDownArrow;=6mr" +
        " ShortLeftArrow;=6mo shortmid;=6qr shortparallel;=6qt ShortRightArrow;=6mq ShortUpArrow;=6mp shy;=4t" +
        " shy=4t Sigma;=pv sigma;=qr sigmaf;=qq sigmav;=qq sim;=6rg simdot;=8dm sime;=6rn simeq;=6rn simg;=8f" +
        "2 simgE;=8f4 siml;=8f1 simlE;=8f3 simne;=6rq simplus;=8bo simrarr;=86q slarr;=6mo SmallCircle;=6qg s" +
        "mallsetminus;=6qe smashp;=8c3 smeparsl;=89w smid;=6qr smile;=6xv smt;=8fe smte;=8fg smtes;=8fg,1e68 " +
        "SOFTcy;=to softcy;=uk solbar;=6yn solb;=890 sol;=1b Sopf;=2kp6 sopf;=2kpw spades;=7kw spadesuit;=7kw" +
        " spar;=6qt sqcap;=6tv sqcaps;=6tv,1e68 sqcup;=6tw sqcups;=6tw,1e68 Sqrt;=6qi sqsub;=6tr sqsube;=6tt " +
        "sqsubset;=6tr sqsubseteq;=6tt sqsup;=6ts sqsupe;=6tu sqsupset;=6ts sqsupseteq;=6tu square;=7fl Squar" +
        "e;=7fl SquareIntersection;=6tv SquareSubset;=6tr SquareSubsetEqual;=6tt SquareSuperset;=6ts SquareSu" +
        "persetEqual;=6tu SquareUnion;=6tw squarf;=7fu squ;=7fl squf;=7fu srarr;=6mq Sscr;=2kku sscr;=2klk ss" +
        "etmn;=6qe ssmile;=6xv sstarf;=6va Star;=6va star;=7ie starf;=7id straightepsilon;=s5 straightphi;=r9" +
        " strns;=4v sub;=6te Sub;=6vk subdot;=8fx subE;=8g5 sube;=6ti subedot;=8g3 submult;=8g1 subnE;=8gb su" +
        "bne;=6tm subplus;=8fz subrarr;=86x subset;=6te Subset;=6vk subseteq;=6ti subseteqq;=8g5 SubsetEqual;" +
        "=6ti subsetneq;=6tm subsetneqq;=8gb subsim;=8g7 subsub;=8gl subsup;=8gj succapprox;=8fs succ;=6t7 su" +
        "cccurlyeq;=6t9 Succeeds;=6t7 SucceedsEqual;=8fk SucceedsSlantEqual;=6t9 SucceedsTilde;=6tb succeq;=8" +
        "fk succnapprox;=8fu succneqq;=8fq succnsim;=6w9 succsim;=6tb SuchThat;=6q3 sum;=6q9 Sum;=6q9 sung;=7" +
        "l6 sup1;=55 sup1=55 sup2;=4y sup2=4y sup3;=4z sup3=4z sup;=6tf Sup;=6vl supdot;=8fy supdsub;=8go sup" +
        "E;=8g6 supe;=6tj supedot;=8g4 Superset;=6tf SupersetEqual;=6tj suphsol;=7ux suphsub;=8gn suplarr;=86" +
        "z supmult;=8g2 supnE;=8gc supne;=6tn supplus;=8g0 supset;=6tf Supset;=6vl supseteq;=6tj supseteqq;=8" +
        "g6 supsetneq;=6tn supsetneqq;=8gc supsim;=8g8 supsub;=8gk supsup;=8gm swarhk;=84m swarr;=6mx swArr;=" +
        "6op swarrow;=6mx swnwar;=84q szlig;=67 szlig=67 Tab;=9 target;=6xi Tau;=pw tau;=qs tbrk;=71w Tcaron;" +
        "=9w tcaron;=9x Tcedil;=9u tcedil;=9v Tcy;=te tcy;=ua tdot;=6hn telrec;=6xh Tfr;=2knr tfr;=2koh there" +
        "4;=6r8 therefore;=6r8 Therefore;=6r8 Theta;=pk theta;=qg thetasym;=r5 thetav;=r5 thickapprox;=6rs th" +
        "icksim;=6rg ThickSpace;=6e7,6bu ThinSpace;=6bt thinsp;=6bt thkap;=6rs thksim;=6rg THORN;=66 THORN=66" +
        " thorn;=72 thorn=72 tilde;=kc Tilde;=6rg TildeEqual;=6rn TildeFullEqual;=6rp TildeTilde;=6rs timesba" +
        "r;=8c1 timesb;=6u8 times;=5z times=5z timesd;=8c0 tint;=6r1 toea;=84o topbot;=6ye topcir;=8hd top;=6" +
        "uc Topf;=2kp7 topf;=2kpx topfork;=8gq tosa;=84p tprime;=6d0 trade;=6jm TRADE;=6jm triangle;=7g5 tria" +
        "ngledown;=7gf triangleleft;=7gj trianglelefteq;=6us triangleq;=6sc triangleright;=7g9 trianglerighte" +
        "q;=6ut tridot;=7ho trie;=6sc triminus;=8ca TripleDot;=6hn triplus;=8c9 trisb;=899 tritime;=8cb trpez" +
        "ium;=736 Tscr;=2kkv tscr;=2kll TScy;=ti tscy;=ue TSHcy;=sr tshcy;=uz Tstrok;=9y tstrok;=9z twixt;=6s" +
        "s twoheadleftarrow;=6n2 twoheadrightarrow;=6n4 Uacute;=62 Uacute=62 uacute;=6y uacute=6y uarr;=6mp U" +
        "arr;=6n3 uArr;=6oh Uarrocir;=85l Ubrcy;=su ubrcy;=v2 Ubreve;=a4 ubreve;=a5 Ucirc;=63 Ucirc=63 ucirc;" +
        "=6z ucirc=6z Ucy;=tf ucy;=ub udarr;=6o5 Udblac;=a8 udblac;=a9 udhar;=86m ufisht;=872 Ufr;=2kns ufr;=" +
        "2koi Ugrave;=61 Ugrave=61 ugrave;=6x ugrave=6x uHar;=86b uharl;=6nz uharr;=6ny uhblk;=7eo ulcorn;=6x" +
        "o ulcorner;=6xo ulcrop;=6xb ultri;=7i0 Umacr;=a2 umacr;=a3 uml;=4o uml=4o UnderBar;=2n UnderBrace;=7" +
        "33 UnderBracket;=71x UnderParenthesis;=731 Union;=6v7 UnionPlus;=6tq Uogon;=aa uogon;=ab Uopf;=2kp8 " +
        "uopf;=2kpy UpArrowBar;=842 uparrow;=6mp UpArrow;=6mp Uparrow;=6oh UpArrowDownArrow;=6o5 updownarrow;" +
        "=6mt UpDownArrow;=6mt Updownarrow;=6ol UpEquilibrium;=86m upharpoonleft;=6nz upharpoonright;=6ny upl" +
        "us;=6tq UpperLeftArrow;=6mu UpperRightArrow;=6mv upsi;=qt Upsi;=r6 upsih;=r6 Upsilon;=px upsilon;=qt" +
        " UpTeeArrow;=6n9 UpTee;=6ud upuparrows;=6o8 urcorn;=6xp urcorner;=6xp urcrop;=6xa Uring;=a6 uring;=a" +
        "7 urtri;=7i1 Uscr;=2kkw uscr;=2klm utdot;=6wg Utilde;=a0 utilde;=a1 utri;=7g5 utrif;=7g4 uuarr;=6o8 " +
        "Uuml;=64 Uuml=64 uuml;=70 uuml=70 uwangle;=887 vangrt;=87w varepsilon;=s5 varkappa;=s0 varnothing;=6" +
        "px varphi;=r9 varpi;=ra varpropto;=6ql varr;=6mt vArr;=6ol varrho;=s1 varsigma;=qq varsubsetneq;=6tm" +
        ",1e68 varsubsetneqq;=8gb,1e68 varsupsetneq;=6tn,1e68 varsupsetneqq;=8gc,1e68 vartheta;=r5 vartriangl" +
        "eleft;=6uq vartriangleright;=6ur vBar;=8h4 Vbar;=8h7 vBarv;=8h5 Vcy;=sy vcy;=tu vdash;=6ua vDash;=6u" +
        "g Vdash;=6uh VDash;=6uj Vdashl;=8h2 veebar;=6uz vee;=6qw Vee;=6v5 veeeq;=6sa vellip;=6we verbar;=3g " +
        "Verbar;=6c6 vert;=3g Vert;=6c6 VerticalBar;=6qr VerticalLine;=3g VerticalSeparator;=7rs VerticalTild" +
        "e;=6rk VeryThinSpace;=6bu Vfr;=2knt vfr;=2koj vltri;=6uq vnsub;=6te,6he vnsup;=6tf,6he Vopf;=2kp9 vo" +
        "pf;=2kpz vprop;=6ql vrtri;=6ur Vscr;=2kkx vscr;=2kln vsubnE;=8gb,1e68 vsubne;=6tm,1e68 vsupnE;=8gc,1" +
        "e68 vsupne;=6tn,1e68 Vvdash;=6ui vzigzag;=87u Wcirc;=ac wcirc;=ad wedbar;=8db wedge;=6qv Wedge;=6v4 " +
        "wedgeq;=6s9 weierp;=6jc Wfr;=2knu wfr;=2kok Wopf;=2kpa wopf;=2kq0 wp;=6jc wr;=6rk wreath;=6rk Wscr;=" +
        "2kky wscr;=2klo xcap;=6v6 xcirc;=7hr xcup;=6v7 xdtri;=7gd Xfr;=2knv xfr;=2kol xharr;=7w7 xhArr;=7wa " +
        "Xi;=pq xi;=qm xlarr;=7w5 xlArr;=7w8 xmap;=7wc xnis;=6wr xodot;=8ao Xopf;=2kpb xopf;=2kq1 xoplus;=8ap" +
        " xotime;=8aq xrarr;=7w6 xrArr;=7w9 Xscr;=2kkz xscr;=2klp xsqcup;=8au xuplus;=8as xutri;=7g3 xvee;=6v" +
        "5 xwedge;=6v4 Yacute;=65 Yacute=65 yacute;=71 yacute=71 YAcy;=tr yacy;=un Ycirc;=ae ycirc;=af Ycy;=t" +
        "n ycy;=uj yen;=4l yen=4l Yfr;=2knw yfr;=2kom YIcy;=sn yicy;=uv Yopf;=2kpc yopf;=2kq2 Yscr;=2kl0 yscr" +
        ";=2klq YUcy;=tq yucy;=um yuml;=73 yuml=73 Yuml;=ag Zacute;=ah zacute;=ai Zcaron;=al zcaron;=am Zcy;=" +
        "t3 zcy;=tz Zdot;=aj zdot;=ak zeetrf;=6js ZeroWidthSpace;=6bv Zeta;=pi zeta;=qe zfr;=2kon Zfr;=6js ZH" +
        "cy;=t2 zhcy;=ty zigrarr;=6ot zopf;=2kq3 Zopf;=6jo Zscr;=2kl1 zscr;=2klr zwj;=6bx zwnj;=6bw"

    /** Each name and prefix, mapped to its code points packed as `cp1 shl 21 or cp2`, or 0 for a prefix. */
    private val TABLE: HashMap<String, Long> = HashMap<String, Long>(16384).also { table ->
        for (entry in DATA.split(' ')) {
            val eq = entry.indexOf('=')
            val name = entry.substring(0, eq)
            val cps = entry.substring(eq + 1).split(',')
            val first = cps[0].toLong(36)
            val second = if (cps.size > 1) cps[1].toLong(36) else 0L
            table[name] = (first shl 21) or second
            for (n in 1 until name.length) table.putIfAbsent(name.substring(0, n), 0L)
        }
        table[""] = 0L
    }

    /**
     * The packed code points for [name] (`cp1 shl 21 or cp2`), 0 when [name] is only a prefix of
     * a reference, or null when no reference starts with it.
     */
    fun lookup(name: CharSequence): Long? = TABLE[name.toString()]
}
