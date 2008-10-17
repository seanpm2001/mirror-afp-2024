(*  Title:       Conflict analysis/Flowgraphs
    Author:      Peter Lammich <peter.lammich@uni-muenster.de>
    Maintainer:  Peter Lammich <peter.lammich@uni-muenster.de>
*)
header "Flowgraphs"
theory Flowgraph
imports Main Misc
begin
text_raw {*\label{thy:Flowgraph}*}

text {*
  We use a flowgraph-based program model that extends the one we used previously \cite{LM07}. 
  A program is represented as an edge annotated graph and a set of procedures. The nodes of the graph are partitioned by the procedures, i.e. every node belongs to exactly one procedure. There are no edges
  between nodes of different procedures. Every procedure has a distinguished entry and return node and a set of monitors it synchronizes on. Additionally, the program has a distinguished {\em main} procedure.
  The edges are annotated with statements. A statement is either a base statement, a procedure call or a thread creation (spawn). Procedure calls and thread creations refer to the called procedure or to the initial procedure
  of the spawned thread, respectively.

  We require that the main procedure and any initial procedure of a spawned thread does not to synchronize on any monitors. This avoids that spawning of a procedure together with entering a monitor is available in our 
  model as an atomic step, which would be an unrealistic assumption for practical problems. Technically, our model would become strictly more powerful without this assumption.


  If we allowed this, our model would become strictly more powerful, 
*}

subsection "Definitions"
  
datatype ('p,'ba) edgeAnnot = Base 'ba | Call 'p | Spawn 'p
types ('n,'p,'ba) edge = "('n \<times> ('p,'ba) edgeAnnot \<times> 'n)"

record ('n,'p,'ba,'m) flowgraph_rec =
  edges :: "('n,'p,'ba) edge set" -- "Set of annotated edges"
  main :: "'p" -- "Main procedure"
  entry :: "'p \<Rightarrow> 'n" -- "Maps a procedure to its entry point"
  return :: "'p \<Rightarrow> 'n" -- "Maps a procedure to its return point"
  mon :: "'p \<Rightarrow> 'm set" -- "Maps procedures to the set of monitors they allocate"
  proc_of :: "'n \<Rightarrow> 'p" -- "Maps a node to the procedure it is contained in"

definition 
  "initialproc fg p == p=main fg \<or> (\<exists>u v. (u,Spawn p,v)\<in>edges fg)"

lemma main_is_initial[simp]: "initialproc fg (main fg)"
  by (unfold initialproc_def) simp

locale flowgraph = struct fg +
  constrains fg :: "('n,'p,'ba,'m,'more) flowgraph_rec_scheme" (* Unnecessary, but perhaps makes it more readable for the unaware reader ;) *)
  -- "Edges are inside procedures only"
  assumes edges_part: "(u,a,v)\<in>edges fg \<Longrightarrow> proc_of fg u = proc_of fg v" 
  -- "The entry point of a procedure must be in that procedure"
  assumes entry_valid[simp]: "proc_of fg (entry fg p) = p" 
  -- "The return point of a procedure must be in that procedure"
  assumes return_valid[simp]: "proc_of fg (return fg p) = p" 
  -- "Initial procedures do not synchronize on any monitors"
  assumes initial_no_mon[simp]: "initialproc fg p \<Longrightarrow> mon fg p = {}" 

subsection "Basic properties"
lemma (in flowgraph) spawn_no_mon[simp]: 
  "(u, Spawn p, v) \<in> edges fg \<Longrightarrow> mon fg p = {}" 
  using initial_no_mon by (unfold initialproc_def, blast)
lemma (in flowgraph) main_no_mon[simp]: "mon fg (main fg) = {}" 
  using initial_no_mon by (unfold initialproc_def, blast)

lemma (in flowgraph) entry_return_same_proc[simp]: 
  "entry fg p = return fg p' \<Longrightarrow> p=p'"
  apply (subgoal_tac "proc_of fg (entry fg p) = proc_of fg (return fg p')")
  apply (simp (no_asm_use))
  by simp

lemma (in flowgraph) entry_entry_same_proc[simp]: 
  "entry fg p = entry fg p' \<Longrightarrow> p=p'"
  apply (subgoal_tac "proc_of fg (entry fg p) = proc_of fg (entry fg p')")
  apply (simp (no_asm_use))
  by simp

lemma (in flowgraph) return_return_same_proc[simp]: 
  "return fg p = return fg p' \<Longrightarrow> p=p'"
  apply (subgoal_tac "proc_of fg (return fg p) = proc_of fg (entry fg p')")
  apply (simp (no_asm_use))
  by simp

subsection "Extra assumptions for flowgraphs"
text_raw {*\label{sec:Flowgraph:extra_asm}*}
text {*
  In order to simplify the definition of our restricted schedules (cf. Section~\ref{thy:Normalization}), we make some extra constraints on flowgraphs. 
  Note that these are no real restrictions, as we can always rewrite flowgraphs to match these constraints, preserving the set of conflicts. We leave it to future work to consider such a rewriting formally. 

  The background of this restrictions is that we want to start an execution of a thread with a procedure call that never returns. This will allow easier technical treatment in Section~\ref{thy:Normalization}. Here we enforce this
  semantic restrictions by syntactic properties of the flowgraph.
*}

text {* The return node of a procedure is called {\em isolated}, if it has no incoming edges and is different from the entry node. A procedure with an isolated return node will never return. 
  See Section~\ref{sec:Normalization:eflowgraph} for a proof of this. *}
definition 
  "isolated_ret fg p == 
    (\<forall>u l. \<not>(u,l,return fg p)\<in>edges fg) \<and> entry fg p \<noteq> return fg p"

text {* The following syntactic restrictions guarantee that each thread's execution starts with a non-returning call. See Section~\ref{sec:Normalization:eflowgraph} for a proof of this. *}
locale eflowgraph = flowgraph fg +
  -- "Initial procedure's entry node isn't equal to its return node"
  assumes initial_no_ret: "initialproc fg p \<Longrightarrow> entry fg p \<noteq> return fg p" 
  -- "The only outgoing edges of initial procedures' entry nodes are call edges to procedures with isolated return node"
  assumes initial_call_no_ret: "\<lbrakk>initialproc fg p; (entry fg p,l,v)\<in>edges fg\<rbrakk> 
    \<Longrightarrow> \<exists>p'. l=Call p' \<and> isolated_ret fg p'" 

subsection {* Example Flowgraph*}
text_raw {* \label{sec:Flowgraph:ex_flowgraph} *}
text {* This section contains a check that there exists a (non-trivial) flowgraph, i.e. that the assumptions made in the @{text flowgraph} and @{text eflowgraph} 
  locales are consistent and have at least one non-trivial model. *}
definition 
  "example_fg == \<lparr> 
    edges = {((0::nat,0::nat),Call 1,(0,1)), ((1,0),Spawn 0,(1,0)), 
             ((1,0),Call 0, (1,0))}, 
    main = 0, 
    entry = \<lambda>p. (p,0), 
    return = \<lambda>p. (p,1), 
    mon = \<lambda>p. if p=1 then {0} else {}, 
    proc_of= \<lambda> (p,x). p \<rparr>"

lemma exists_eflowgraph: "eflowgraph example_fg"
  apply (unfold_locales)
  apply (unfold example_fg_def)
  apply simp
  apply fast
  apply simp
  apply simp
  apply (simp add: initialproc_def)
  apply (simp add: initialproc_def)
  apply (simp add: initialproc_def isolated_ret_def)
  done


end
