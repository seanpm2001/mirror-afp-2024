(*  Title:      Jinja/J/WellTypeRT.thy
    ID:         $Id: WellTypeRT.thy,v 1.3 2006-05-27 15:32:27 makarius Exp $
    Author:     Tobias Nipkow
    Copyright   2003 Technische Universitaet Muenchen
*)

header {* \isaheader{Runtime Well-typedness} *}

theory WellTypeRT
imports WellType
begin

consts
  WTrt :: "J_prog \<Rightarrow> heap \<Rightarrow> (env \<times> expr      \<times> ty     )set"
  WTrts:: "J_prog \<Rightarrow> heap \<Rightarrow> (env \<times> expr list \<times> ty list)set"

(*<*)
syntax (xsymbols)
  WTrt :: "[J_prog,env,heap,expr,ty] \<Rightarrow> bool"
        ("_,_,_ \<turnstile> _ : _"   [51,51,51]50)
  WTrts:: "[J_prog,env,heap,expr list, ty list] \<Rightarrow> bool"
        ("_,_,_ \<turnstile> _ [:] _" [51,51,51]50)
(*>*)

translations
  "P,E,h \<turnstile> e : T"  ==  "(E,e,T) \<in> WTrt P h"
  "P,E,h \<turnstile> es[:]Ts"  ==  "(E,es,Ts) \<in> WTrts P h"

inductive "WTrt P h" "WTrts P h"
intros
  
WTrtNew:
  "is_class P C  \<Longrightarrow>
  P,E,h \<turnstile> new C : Class C"

WTrtCast:
  "\<lbrakk> P,E,h \<turnstile> e : T; is_refT T; is_class P C \<rbrakk>
  \<Longrightarrow> P,E,h \<turnstile> Cast C e : Class C"

WTrtVal:
  "typeof\<^bsub>h\<^esub> v = Some T \<Longrightarrow>
  P,E,h \<turnstile> Val v : T"

WTrtVar:
  "E V = Some T  \<Longrightarrow>
  P,E,h \<turnstile> Var V : T"
(*
WTrtBinOp:
  "\<lbrakk> P,E,h \<turnstile> e\<^isub>1 : T\<^isub>1;  P,E,h \<turnstile> e\<^isub>2 : T\<^isub>2;
    case bop of Eq \<Rightarrow> T = Boolean
              | Add \<Rightarrow> T\<^isub>1 = Integer \<and> T\<^isub>2 = Integer \<and> T = Integer \<rbrakk>
   \<Longrightarrow> P,E,h \<turnstile> e\<^isub>1 \<guillemotleft>bop\<guillemotright> e\<^isub>2 : T"
*)
WTrtBinOpEq:
  "\<lbrakk> P,E,h \<turnstile> e\<^isub>1 : T\<^isub>1;  P,E,h \<turnstile> e\<^isub>2 : T\<^isub>2 \<rbrakk>
  \<Longrightarrow> P,E,h \<turnstile> e\<^isub>1 \<guillemotleft>Eq\<guillemotright> e\<^isub>2 : Boolean"

WTrtBinOpAdd:
  "\<lbrakk> P,E,h \<turnstile> e\<^isub>1 : Integer;  P,E,h \<turnstile> e\<^isub>2 : Integer \<rbrakk>
  \<Longrightarrow> P,E,h \<turnstile> e\<^isub>1 \<guillemotleft>Add\<guillemotright> e\<^isub>2 : Integer"

WTrtLAss:
  "\<lbrakk> E V = Some T;  P,E,h \<turnstile> e : T';  P \<turnstile> T' \<le> T \<rbrakk>
   \<Longrightarrow> P,E,h \<turnstile> V:=e : Void"

WTrtFAcc:
  "\<lbrakk> P,E,h \<turnstile> e : Class C; P \<turnstile> C has F:T in D \<rbrakk> \<Longrightarrow>
  P,E,h \<turnstile> e\<bullet>F{D} : T"

WTrtFAccNT:
  "P,E,h \<turnstile> e : NT \<Longrightarrow>
  P,E,h \<turnstile> e\<bullet>F{D} : T"

WTrtFAss:
  "\<lbrakk> P,E,h \<turnstile> e\<^isub>1 : Class C;  P \<turnstile> C has F:T in D; P,E,h \<turnstile> e\<^isub>2 : T\<^isub>2;  P \<turnstile> T\<^isub>2 \<le> T \<rbrakk>
  \<Longrightarrow> P,E,h \<turnstile> e\<^isub>1\<bullet>F{D}:=e\<^isub>2 : Void"

WTrtFAssNT:
  "\<lbrakk> P,E,h \<turnstile> e\<^isub>1:NT; P,E,h \<turnstile> e\<^isub>2 : T\<^isub>2 \<rbrakk>
  \<Longrightarrow> P,E,h \<turnstile> e\<^isub>1\<bullet>F{D}:=e\<^isub>2 : Void"

WTrtCall:
  "\<lbrakk> P,E,h \<turnstile> e : Class C; P \<turnstile> C sees M:Ts \<rightarrow> T = (pns,body) in D;
     P,E,h \<turnstile> es [:] Ts'; P \<turnstile> Ts' [\<le>] Ts \<rbrakk>
  \<Longrightarrow> P,E,h \<turnstile> e\<bullet>M(es) : T"

WTrtCallNT:
  "\<lbrakk> P,E,h \<turnstile> e : NT; P,E,h \<turnstile> es [:] Ts \<rbrakk>
  \<Longrightarrow> P,E,h \<turnstile> e\<bullet>M(es) : T"

WTrtBlock:
  "P,E(V\<mapsto>T),h \<turnstile> e : T'  \<Longrightarrow>
  P,E,h \<turnstile> {V:T; e} : T'"

WTrtSeq:
  "\<lbrakk> P,E,h \<turnstile> e\<^isub>1:T\<^isub>1;  P,E,h \<turnstile> e\<^isub>2:T\<^isub>2 \<rbrakk>
  \<Longrightarrow> P,E,h \<turnstile> e\<^isub>1;;e\<^isub>2 : T\<^isub>2"

WTrtCond:
  "\<lbrakk> P,E,h \<turnstile> e : Boolean;  P,E,h \<turnstile> e\<^isub>1:T\<^isub>1;  P,E,h \<turnstile> e\<^isub>2:T\<^isub>2;
     P \<turnstile> T\<^isub>1 \<le> T\<^isub>2 \<or> P \<turnstile> T\<^isub>2 \<le> T\<^isub>1; P \<turnstile> T\<^isub>1 \<le> T\<^isub>2 \<longrightarrow> T = T\<^isub>2; P \<turnstile> T\<^isub>2 \<le> T\<^isub>1 \<longrightarrow> T = T\<^isub>1 \<rbrakk>
  \<Longrightarrow> P,E,h \<turnstile> if (e) e\<^isub>1 else e\<^isub>2 : T"

WTrtWhile:
  "\<lbrakk> P,E,h \<turnstile> e : Boolean;  P,E,h \<turnstile> c:T \<rbrakk>
  \<Longrightarrow>  P,E,h \<turnstile> while(e) c : Void"

WTrtThrow:
  "\<lbrakk> P,E,h \<turnstile> e : T\<^isub>r; is_refT T\<^isub>r \<rbrakk> \<Longrightarrow>
  P,E,h \<turnstile> throw e : T"

WTrtTry:
  "\<lbrakk> P,E,h \<turnstile> e\<^isub>1 : T\<^isub>1;  P,E(V \<mapsto> Class C),h \<turnstile> e\<^isub>2 : T\<^isub>2; P \<turnstile> T\<^isub>1 \<le> T\<^isub>2 \<rbrakk>
  \<Longrightarrow> P,E,h \<turnstile> try e\<^isub>1 catch(C V) e\<^isub>2 : T\<^isub>2"

-- "well-typed expression lists"

WTrtNil:
  "P,E,h \<turnstile> [] [:] []"

WTrtCons:
  "\<lbrakk> P,E,h \<turnstile> e : T;  P,E,h \<turnstile> es [:] Ts \<rbrakk>
  \<Longrightarrow>  P,E,h \<turnstile> e#es [:] T#Ts"

(*<*)
declare WTrt_WTrts.intros[intro!] WTrtNil[iff]
declare
  WTrtFAcc[rule del] WTrtFAccNT[rule del]
  WTrtFAss[rule del] WTrtFAssNT[rule del]
  WTrtCall[rule del] WTrtCallNT[rule del]

lemmas WTrt_induct = WTrt_WTrts.induct [split_format (complete)]
  and WTrt_inducts = WTrt_WTrts.inducts [split_format (complete)]
(*>*)


subsection{*Easy consequences*}

lemma [iff]: "(P,E,h \<turnstile> [] [:] Ts) = (Ts = [])"
(*<*)
apply(rule iffI)
apply (auto elim: WTrt_WTrts.elims)
done
(*>*)

lemma [iff]: "(P,E,h \<turnstile> e#es [:] T#Ts) = (P,E,h \<turnstile> e : T \<and> P,E,h \<turnstile> es [:] Ts)"
(*<*)
apply(rule iffI)
apply (auto elim: WTrt_WTrts.elims)
done
(*>*)

lemma [iff]: "(P,E,h \<turnstile> (e#es) [:] Ts) =
  (\<exists>U Us. Ts = U#Us \<and> P,E,h \<turnstile> e : U \<and> P,E,h \<turnstile> es [:] Us)"
(*<*)
apply(rule iffI)
apply (auto elim: WTrt_WTrts.elims)
done
(*>*)

lemma [simp]: "\<forall>Ts. (P,E,h \<turnstile> es\<^isub>1 @ es\<^isub>2 [:] Ts) =
  (\<exists>Ts\<^isub>1 Ts\<^isub>2. Ts = Ts\<^isub>1 @ Ts\<^isub>2 \<and> P,E,h \<turnstile> es\<^isub>1 [:] Ts\<^isub>1 & P,E,h \<turnstile> es\<^isub>2[:]Ts\<^isub>2)"
(*<*)
apply(induct_tac es\<^isub>1)
 apply simp
apply clarsimp
apply(erule thin_rl)
apply (rule iffI)
 apply clarsimp
 apply(rule exI)+
 apply(rule conjI)
  prefer 2 apply blast
 apply simp
apply fastsimp
done
(*>*)

lemma [iff]: "P,E,h \<turnstile> Val v : T = (typeof\<^bsub>h\<^esub> v = Some T)"
(*<*)
apply(rule iffI)
apply (auto elim: WTrt_WTrts.elims)
done
(*>*)

lemma [iff]: "P,E,h \<turnstile> Var v : T = (E v = Some T)"
(*<*)
apply(rule iffI)
apply (auto elim: WTrt_WTrts.elims)
done
(*>*)

lemma [iff]: "P,E,h \<turnstile> e\<^isub>1;;e\<^isub>2 : T\<^isub>2 = (\<exists>T\<^isub>1. P,E,h \<turnstile> e\<^isub>1:T\<^isub>1 \<and> P,E,h \<turnstile> e\<^isub>2:T\<^isub>2)"
(*<*)
apply(rule iffI)
apply (auto elim: WTrt_WTrts.elims)
done
(*>*)

lemma [iff]: "P,E,h \<turnstile> {V:T; e} : T'  =  (P,E(V\<mapsto>T),h \<turnstile> e : T')"
(*<*)
apply(rule iffI)
apply (auto elim: WTrt_WTrts.elims)
done
(*>*)
(*<*)
inductive_cases WTrt_elim_cases[elim!]:
  "P,E,h \<turnstile> v :=e : T"
  "P,E,h \<turnstile> if (e) e\<^isub>1 else e\<^isub>2 : T"
  "P,E,h \<turnstile> while(e) c : T"
  "P,E,h \<turnstile> throw e : T"
  "P,E,h \<turnstile> try e\<^isub>1 catch(C V) e\<^isub>2 : T"
  "P,E,h \<turnstile> Cast D e : T"
  "P,E,h \<turnstile> e\<bullet>F{D} : T"
  "P,E,h \<turnstile> e\<bullet>F{D} := v : T"
  "P,E,h \<turnstile> e\<^isub>1 \<guillemotleft>bop\<guillemotright> e\<^isub>2 : T"
  "P,E,h \<turnstile> new C : T"
  "P,E,h \<turnstile> e\<bullet>M{D}(es) : T"
(*>*)

subsection{*Some interesting lemmas*}

lemma WTrts_Val[simp]:
 "\<And>Ts. (P,E,h \<turnstile> map Val vs [:] Ts) = (map (typeof\<^bsub>h\<^esub>) vs = map Some Ts)"
(*<*)
apply(induct vs)
 apply simp
apply(case_tac Ts)
 apply simp
apply simp
done
(*>*)


lemma WTrts_same_length: "\<And>Ts. P,E,h \<turnstile> es [:] Ts \<Longrightarrow> length es = length Ts"
(*<*)by(induct es type:list)auto(*>*)


lemma WTrt_env_mono:
  "P,E,h \<turnstile> e : T \<Longrightarrow> (\<And>E'. E \<subseteq>\<^sub>m E' \<Longrightarrow> P,E',h \<turnstile> e : T)" and
  "P,E,h \<turnstile> es [:] Ts \<Longrightarrow> (\<And>E'. E \<subseteq>\<^sub>m E' \<Longrightarrow> P,E',h \<turnstile> es [:] Ts)"
(*<*)
apply(induct rule: WTrt_inducts)
apply(simp add: WTrtNew)
apply(fastsimp simp: WTrtCast)
apply(fastsimp simp: WTrtVal)
apply(simp add: WTrtVar map_le_def dom_def)
apply(fastsimp simp add: WTrtBinOpEq)
apply(fastsimp simp add: WTrtBinOpAdd)
apply(force simp: map_le_def)
apply(fastsimp simp: WTrtFAcc)
apply(simp add: WTrtFAccNT)
apply(fastsimp simp: WTrtFAss)
apply(fastsimp simp: WTrtFAssNT)
apply(fastsimp simp: WTrtCall)
apply(fastsimp simp: WTrtCallNT)
apply(simp add: WTrtNil)
apply(simp add: WTrtCons)
apply(fastsimp simp: map_le_def)
apply(fastsimp)
apply(fastsimp simp: WTrtSeq)
apply(fastsimp simp: WTrtWhile)
apply(fastsimp simp: WTrtThrow)
apply(auto simp: WTrtTry map_le_def dom_def)
done
(*>*)


lemma WTrt_hext_mono: "P,E,h \<turnstile> e : T \<Longrightarrow> h \<unlhd> h' \<Longrightarrow> P,E,h' \<turnstile> e : T"
and WTrts_hext_mono: "P,E,h \<turnstile> es [:] Ts \<Longrightarrow> h \<unlhd> h' \<Longrightarrow> P,E,h' \<turnstile> es [:] Ts"
(*<*)
apply(induct rule: WTrt_inducts)
apply(simp add: WTrtNew)
apply(fastsimp simp: WTrtCast)
apply(fastsimp simp: WTrtVal dest:hext_typeof_mono)
apply(simp add: WTrtVar)
apply(fastsimp simp add: WTrtBinOpEq)
apply(fastsimp simp add: WTrtBinOpAdd)
apply(fastsimp simp add: WTrtLAss)
apply(fast intro: WTrtFAcc)
apply(simp add: WTrtFAccNT)
apply(fastsimp simp: WTrtFAss del:WTrt_WTrts.intros WTrt_elim_cases)
apply(fastsimp simp: WTrtFAssNT)
apply(fastsimp simp: WTrtCall)
apply(fastsimp simp: WTrtCallNT)
apply(fastsimp)
apply(fastsimp simp add: WTrtSeq)
apply(fastsimp simp add: WTrtCond)
apply(fastsimp simp add: WTrtWhile)
apply(fastsimp simp add: WTrtThrow)
apply(fastsimp simp: WTrtTry)
apply(simp add: WTrtNil)
apply(simp add: WTrtCons)
done
(*>*)


lemma WT_implies_WTrt: "P,E \<turnstile> e :: T \<Longrightarrow> P,E,h \<turnstile> e : T"
and WTs_implies_WTrts: "P,E \<turnstile> es [::] Ts \<Longrightarrow> P,E,h \<turnstile> es [:] Ts"
(*<*)
apply(induct rule: WT_WTs_inducts)
apply fast
apply (fast)
apply(fastsimp dest:typeof_lit_typeof)
apply(simp)
apply(fastsimp)
apply(fastsimp)
apply(fastsimp)
apply(fastsimp simp: WTrtFAcc has_visible_field)
apply(fastsimp simp: WTrtFAss dest: has_visible_field)
apply(fastsimp simp: WTrtCall)
apply(fastsimp)
apply(fastsimp)
apply(fastsimp simp: WTrtCond)
apply(fastsimp)
apply(fastsimp)
apply(fastsimp)
apply(simp)
apply(simp)
done
(*>*)


end
