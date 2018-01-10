(*
Author:  Christian Sternagel <c.sternagel@gmail.com>
License: LGPL
*)

theory Simple_Algorithm
  imports
    Linear_Diophantine_Equations
    Minimize_Wrt
begin

(*TODO: move?*)
lemma concat_map_nth0: "xs \<noteq> [] \<Longrightarrow> f (xs ! 0) \<noteq> [] \<Longrightarrow> concat (map f xs) ! 0 = f (xs ! 0) ! 0"
  by (induct xs) (auto simp: nth_append)


subsection \<open>Lexicographic Enumeration of Potential Solutions\<close>

fun rlex2 :: "(nat list \<times> nat list) \<Rightarrow> (nat list \<times> nat list) \<Rightarrow> bool"  (infix "<\<^sub>r\<^sub>l\<^sub>e\<^sub>x\<^sub>2" 50)
  where
    "(xs, ys) <\<^sub>r\<^sub>l\<^sub>e\<^sub>x\<^sub>2 (us, vs) \<longleftrightarrow> xs @ ys <\<^sub>r\<^sub>l\<^sub>e\<^sub>x us @ vs"

lemma rlex2_irrefl:
  "\<not> x <\<^sub>r\<^sub>l\<^sub>e\<^sub>x\<^sub>2 x"
  by (cases x) (auto simp: rlex_irrefl)

lemma rlex2_not_sym: "x <\<^sub>r\<^sub>l\<^sub>e\<^sub>x\<^sub>2 y \<Longrightarrow> \<not> y <\<^sub>r\<^sub>l\<^sub>e\<^sub>x\<^sub>2 x"
  using rlex_not_sym by (cases x; cases y; simp)

lemma less_imp_rlex2: "\<not> (case x of (x, y) \<Rightarrow> \<lambda>(u, v). \<not> x @ y <\<^sub>v u @ v) y \<Longrightarrow> x <\<^sub>r\<^sub>l\<^sub>e\<^sub>x\<^sub>2 y"
  using less_imp_rlex by (cases x; cases y; auto)

lemma rlex2_trans:
  assumes "x <\<^sub>r\<^sub>l\<^sub>e\<^sub>x\<^sub>2 y"
    and "y <\<^sub>r\<^sub>l\<^sub>e\<^sub>x\<^sub>2 z"
  shows "x <\<^sub>r\<^sub>l\<^sub>e\<^sub>x\<^sub>2 z"
  using assms
proof -
  obtain x1 x2 y1 y2 z1 z2 where "(x1, x2) = x" and "(y1, y2) = y" and "(z1, z2) = z"
    using prod.collapse by blast
  then show ?thesis
    using assms rlex_def
      lex_trans[of "rev (x1 @ x2)" "rev (y1 @ y2)" "rev (z1 @ z2)"]
    by (auto)
qed


text \<open>Generate all lists (of natural numbers) of length \<open>n\<close> with elements bounded by \<open>B\<close>.\<close>
fun gen :: "nat \<Rightarrow> nat \<Rightarrow> nat list list"
  where
    "gen B 0 = [[]]"
  | "gen B (Suc n) = [x#xs . xs \<leftarrow> gen B n, x \<leftarrow> [0 ..< B + 1]]"

definition "generate A B m n = tl [(x, y) . y \<leftarrow> gen B n, x \<leftarrow> gen A m]"

definition "check a b = filter (\<lambda>(x, y). a \<bullet> x = b \<bullet> y)"

definition "minimize = minimize_wrt (\<lambda>(x, y) (u, v). \<not> x @ y <\<^sub>v u @ v)"

definition "solutions a b =
  (let A = Max (set b); B = Max (set a); m = length a; n = length b
  in minimize (check a b (generate A B m n)))"

lemma set_gen: "set (gen B n) = {xs. length xs = n \<and> (\<forall>i<n. xs ! i \<le> B)}" (is "_ = ?A n")
proof (induct n)
  case [simp]: (Suc n)
  { fix xs assume "xs \<in> ?A (Suc n)"
    then have "xs \<in> set (gen B (Suc n))"
      by (cases xs) (force simp: all_Suc_conv)+ }
  then show ?case by (auto simp: less_Suc_eq_0_disj)
qed simp

abbreviation "gen2 A B m n \<equiv> [(x, y) . y \<leftarrow> gen B n, x \<leftarrow> gen A m]"

lemma sorted_wrt_gen:
  "sorted_wrt (<\<^sub>r\<^sub>l\<^sub>e\<^sub>x) (gen B n)"
  by (induct n) (auto simp: rlex_Cons sorted_wrt_append
    intro!: sorted_wrt_concat_map [where h = id, simplified] sorted_wrt_map_mono [of "(<)"])

lemma sorted_wrt_gen2: "sorted_wrt (<\<^sub>r\<^sub>l\<^sub>e\<^sub>x\<^sub>2) (gen2 A B m n)"
  by (intro sorted_wrt_concat_map_map [where Q = "(<\<^sub>r\<^sub>l\<^sub>e\<^sub>x)"] sorted_wrt_gen)
    (auto simp: set_gen rlex_def intro:  lex_append_left lex_append_right)

lemma gen_ne [simp]:
  "gen B n \<noteq> []"
  by (induct n) auto

lemma gen2_ne:
  "gen2 A B m n \<noteq> []"
  by auto

lemma sorted_wrt_generate: "sorted_wrt (<\<^sub>r\<^sub>l\<^sub>e\<^sub>x\<^sub>2) (generate A B m n)"
  by (auto simp: generate_def intro: sorted_wrt_tl sorted_wrt_gen2)

abbreviation "check_generate a b \<equiv> check a b (generate (Max (set b)) (Max (set a)) (length a) (length b))"

lemma sorted_wrt_check_generate: "sorted_wrt (<\<^sub>r\<^sub>l\<^sub>e\<^sub>x\<^sub>2) (check_generate a b)"
  by (auto simp: check_def intro: sorted_wrt_filter sorted_wrt_generate)

lemma in_tl_gen2: "x \<in> set (tl (gen2 A B m n)) \<Longrightarrow> x \<in> set (gen2 A B m n)"
  by (rule list.set_sel) simp

lemma gen_nth0 [simp]: "gen B n ! 0 = zeroes n"
  by (induct n) (auto simp: nth_append concat_map_nth0)

lemma gen2_nth0 [simp]:
  "gen2 A B m n ! 0 = (zeroes m, zeroes n)"
  by (auto simp: concat_map_nth0)

lemma set_gen2: "set (gen2 A B m n) =
  {(xs, ys). length xs = m \<and> length ys = n \<and> (\<forall>i<m. xs ! i \<le> A) \<and> (\<forall>j<n. ys ! j \<le> B)}"
  by (auto simp: set_gen)

lemma gen2_unique:
  assumes "i < length (gen2 A B m n)"
    and "j < length (gen2 A B m n)"
    and "i < j"
  shows "gen2 A B m n ! i \<noteq> gen2 A B m n ! j"
  using sorted_wrt_nth_less [OF sorted_wrt_gen2 assms]
  by (auto simp: rlex2_irrefl)

lemma zeroes_ni_tl_gen2:
  "(zeroes m, zeroes n) \<notin> set (tl (gen2 A B m n))"
proof -
  have "gen2 A B m n ! 0 = (zeroes m, zeroes n)" by (auto simp: generate_def)
  with gen2_unique [of 0 A m B n] show ?thesis
    by (metis (no_types, lifting) Suc_eq_plus1 gr0I gr_implies_not0 in_set_conv_nth length_tl lessI less_diff_conv nth_tl)
qed

lemma set_generate:
  "set (generate A B m n) = {(x, y). (x, y) \<noteq> (zeroes m, zeroes n) \<and> (x, y) \<in> set (gen2 A B m n)}"
proof
  show "set (generate A B m n)
    \<subseteq> {(x, y).(x, y) \<noteq> (zeroes m, zeroes n) \<and> (x, y) \<in> set (gen2 A B m n)}"
    using in_tl_gen2 and mem_Collect_eq and zeroes_ni_tl_gen2 by (auto simp: generate_def)
next
  have "(zeroes m, zeroes n) = hd (gen2 A B m n)"
    by (simp add: hd_conv_nth)
  moreover have "set (gen2 A B m n) = set (generate A B m n) \<union> {(zeroes m, zeroes n)}"
    by (metis Un_empty_right generate_def Un_insert_right gen2_ne calculation list.exhaust_sel list.simps(15))
  ultimately show " {(x, y). (x, y) \<noteq> (zeroes m, zeroes n) \<and> (x, y) \<in> set (gen2 A B m n)}
    \<subseteq> set (generate A B m n)"
    by blast
qed

lemma set_check_generate:
  "set (check_generate a b) = {(x, y).
    (x, y) \<noteq> (zeroes (length a), zeroes (length b)) \<and>
    length x = length a \<and> length y = length b \<and> a \<bullet> x = b \<bullet> y \<and>
    (\<forall>i<length a. x ! i \<le> Max (set b)) \<and> (\<forall>j<length b. y ! j \<le> Max (set a))}"
  unfolding check_def and set_filter and set_generate and set_gen2 by auto

lemma set_solutions_iff:
  "set (solutions a b) =
    {(x, y) \<in> set (check_generate a b). \<not> (\<exists>(u, v)\<in>set (check_generate a b). u @ v <\<^sub>v x @ y)}"
proof -
  { fix x
    note * = in_minimize_wrt_iff [where xs = "check_generate a b" and P = "(\<lambda>(x, y) (u, v). \<not> x @ y <\<^sub>v u @ v)" and Q = "(<\<^sub>r\<^sub>l\<^sub>e\<^sub>x\<^sub>2)"]
    have "x \<in> set (minimize (check_generate a b)) \<longleftrightarrow>
      x \<in> set (check_generate a b) \<and> (\<forall>y\<in>set (check_generate a b). (case y of (x, y) \<Rightarrow> \<lambda>(u, v). \<not> x @ y <\<^sub>v u @ v) x)"
      using rlex_not_sym and less_imp_rlex
      by (unfold minimize_def, intro *) (auto intro: sorted_wrt_check_generate) }
  then show ?thesis by (auto simp: solutions_def)
qed


subsubsection \<open>Completeness: every minimal solution is generated by \<open>solutions\<close>\<close>

lemma (in hlde) solutions_complete:
  "Minimal_Solutions \<subseteq> set (solutions a b)"
proof (rule subrelI)
  let ?A = "Max (set b)"
  let ?B = "Max (set a)"
  fix x y assume min: "(x, y) \<in> Minimal_Solutions"
  then have "\<forall>i<m. x ! i \<le> maxne0 y b" and "\<forall>j<n. y ! j \<le> maxne0 x a"
    and "length x = m" and "length y = n"
    by (auto simp: Minimal_Solutions_length max_coeff_bound)
  then have "(x, y) \<in> set (generate ?A ?B m n)"
    unfolding set_generate and set_gen2
    using maxne0_le_Max and Minimal_Solutions_gt0 [OF min]
    by (auto intro: le_trans simp: set_gen2)
  then have "(x, y) \<in> set (check a b (generate ?A ?B m n))"
    using min by (auto simp: check_def Minimal_Solutions_def Solutions_def)
  moreover have "\<forall>(u, v) \<in> set (check a b (generate ?A ?B m n)). \<not> u @ v <\<^sub>v x @ y"
    using min and no0
    by (auto simp: check_def set_generate neq_0_iff' set_gen nonzero_iff dest!: Minimal_Solutions_min)
  ultimately show "(x, y) \<in> set (solutions a b)"
    by (auto intro: in_minimize_wrtI simp: solutions_def minimize_def)
qed

lemma (in hlde) solutions_sound:
  "set (solutions a b) \<subseteq> Minimal_Solutions"
proof (rule subrelI)
  fix x y assume sol: "(x, y) \<in> set (solutions a b)"
  show "(x, y) \<in> Minimal_Solutions"
  proof (rule Minimal_SolutionsI')
    show *: "(x, y) \<in> Solutions"
      using sol by (auto simp: set_solutions_iff Solutions_def check_def set_generate set_gen)
    show "nonzero x"
      using sol and nonzero_iff and replicate_eqI and nonzero_Solutions_iff [OF *]
      by (fastforce simp: solutions_def minimize_def check_def set_generate set_gen dest!: minimize_wrt_subset [THEN subsetD])
    show "\<not> (\<exists>(u, v)\<in>Minimal_Solutions. u @ v <\<^sub>v x @ y)"
    proof
      have min_cg: "(x, y) \<in> set (minimize (check_generate a b))"
        using sol by (auto simp: solutions_def)
      note * = in_minimize_wrt_False [OF _ sorted_wrt_check_generate min_cg [unfolded minimize_def]]

      assume "\<exists>(u, v)\<in>Minimal_Solutions. u @ v <\<^sub>v x @ y"
      then obtain u and v where "(u, v) \<in> Minimal_Solutions" and less: "u @ v <\<^sub>v x @ y" by blast
      then have "(u, v) \<in> set (solutions a b)" by (auto intro: solutions_complete [THEN subsetD])
      then have "(u, v) \<in> set (check_generate a b)"
        by (auto simp: solutions_def minimize_def dest: minimize_wrt_subset [THEN subsetD])
      from * [OF _ _ _ this] and less show False
        using less_imp_rlex and rlex_not_sym by force
    qed
  qed
qed

lemma (in hlde) set_solutions [simp]: "set (solutions a b) = Minimal_Solutions"
  using solutions_sound and solutions_complete by blast

end
