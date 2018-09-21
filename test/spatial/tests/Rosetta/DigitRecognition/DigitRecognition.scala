package spatial.tests.Rosetta

import spatial.dsl._
import spatial.targets._


@spatial class DigitRecognition extends SpatialTest {

    type LabelType	 		= UInt8

	@struct case class IntAndIndex(value : Int, inx : I32)

	@struct case class LabelAndIndex(dist : Int, label : LabelType, inx : I32)
    
    @struct case class DigitType(d1 : UInt64, d2 : UInt64, d3 : UInt64, d4 : UInt64)

	@struct case class DigitType1(d1 : UInt64, d2 : UInt64)

    @struct case class DigitType2(d3 : UInt64, d4 : UInt64)


	val digit_length 		= 196
	val digit_field_num 	= 4

	val simulate_file_string 	= "spatial-lang/apps/src/Rosetta/"
	val empty_str = ""


	val num_training = 18000
	val class_size 	 = 1800 /* number of digits in training set that belong to each class */
	val num_test 	 = 2000


	/* Parameters to tune */
	val k_const 				= 5 /* Number of nearest neighbors to handle */
	val par_factor  			= 1
	val parLoad 				= 1

	def network_sort(knn_set 		: RegFile2[Int], 
					 label_set 		: RegFile2[LabelType],
					 p : I32) : Unit = {
		/* Odd-Even network sort on knn_set in-place */
		val num_elems = k_const /* should be size of each knn_set */
		Foreach(num_elems by 1) { i =>
			val start_i = mux( (i & 1.to[I32]) == 1.to[I32], 0.to[I32], 1.to[I32] )
			
			val oe_par_factor = num_elems >> 1  // might not work if regfile size is even 
			Foreach(start_i until num_elems - 1 by 2 par oe_par_factor) { k =>
				val value_1 = knn_set(p, k)
				val value_2 = knn_set(p, k + 1)
				val smaller_value = mux(value_1 <  value_2, value_1, value_2)
				val bigger_value  = mux(value_1 >= value_2, value_1, value_2)

				val label_1 = label_set(p, k)
				val label_2 = label_set(p, k + 1)
				val first_label  = mux(value_1 <  value_2, label_1, label_2)
				val second_label = mux(value_1 >= value_2, label_1, label_2)

				knn_set(p, k) 	  = smaller_value 
				knn_set(p, k + 1) = bigger_value

				label_set(p, k)		 = first_label
				label_set(p, k + 1)  = second_label
			}
		}
	}

	
	def merge_sorted_lists(par_knn_set 		: RegFile2[Int], 
						   par_label_set	: RegFile2[LabelType],
						   sorted_label_set	: RegFile1[LabelType]) : Unit = {


		val partition_index_list = RegFile[I32](par_factor)
		Foreach(par_factor by 1 par par_factor) { p => partition_index_list(p) = 0.to[Int] }

		Sequential.Foreach(k_const by 1) { k => //doesn't work without Sequential 
			val insert_this_label =	Reduce(Reg[LabelAndIndex])(par_factor by 1 par par_factor) { p =>
										val check_at_index = partition_index_list(p)
										val curr_index 	   = k_const + check_at_index

										val current_label = par_label_set(p, curr_index)
										val current_dist  = par_knn_set(p, curr_index)

										LabelAndIndex(current_dist, current_label, p)
									}{ (a, b) => mux(a.dist < b.dist, a, b) }
			Parallel {
				sorted_label_set(k) = insert_this_label.label 
				partition_index_list(insert_this_label.inx) = partition_index_list(insert_this_label.inx) + 1.to[I32]
			}
		}

	} 


	def hamming_distance(x1 : 	DigitType1, x2 : DigitType2 ) : Int = {

	
		// simplest implementation for counting 1-bits in bitstring
		val sum_bits_tmp = RegFile[Int](4)
		val bits_d1 = List.tabulate(64){ i =>
						val d1 = x1.d1 
						d1.bit(i).to[Int]
					  }.reduce(_ + _)
		sum_bits_tmp(0) = bits_d1

		val bits_d2 =  List.tabulate(64) { i =>
						val d2 = x1.d2 
						d2.bit(i).to[Int] 
					  }.reduce(_ + _)
		sum_bits_tmp(1) = bits_d2 

		val bits_d3 =  List.tabulate(64) { i =>
						val d3 = x2.d3 
						d3.bit(i).to[Int] 
					  }.reduce(_ + _)
		sum_bits_tmp(2) = bits_d3 
		
		val bits_d4 =  List.tabulate(64){ i =>
						val d4 = x2.d4
						d4.bit(i).to[Int] 
					  }.reduce(_ + _)
		sum_bits_tmp(3) = bits_d4 
		
		sum_bits_tmp(0) + sum_bits_tmp(1) + sum_bits_tmp(2) + sum_bits_tmp(3)
	}

	def update_knn(test_inst1 	: DigitType1, 
				   test_inst2 	: DigitType2,	
				   train_inst1 	: DigitType1, 
				   train_inst2 	: DigitType2,
				   min_dists	: RegFile2[Int],
				   label_list   : RegFile2[LabelType],
				   p 			: I32,
				   train_label  : LabelType) : Unit = {

		val digit_xored1 = DigitType1(test_inst1.d1 ^ train_inst1.d1, test_inst1.d2 ^ train_inst1.d2)
		val digit_xored2 = DigitType2(test_inst2.d3 ^ train_inst2.d3, test_inst2.d4 ^ train_inst2.d4)

		val max_dist = Reg[IntAndIndex](IntAndIndex(0.to[Int], 0.to[I32]))
		Pipe { max_dist := IntAndIndex(0.to[Int], 0.to[I32]) }

		val dist = Reg[Int](0)
		Parallel { 
			Pipe { dist := hamming_distance(digit_xored1, digit_xored2) }

			Reduce(max_dist)(k_const by 1) { k =>
				IntAndIndex(min_dists(p, k), k)
			} {(dist1,dist2) => mux(dist1.value > dist2.value, dist1, dist2) }
		}	

		if (dist.value < max_dist.value.value) {
			min_dists(p, max_dist.value.inx) = dist.value  
			label_list(p, max_dist.value.inx) = train_label
		}
		
	}

	def initialize(min_dists	: RegFile2[Int],
				   label_list	: RegFile2[LabelType],
				   vote_list	: RegFile1[Int]) = {
		val vote_len = vote_list.length /* should always be 10 */

		Parallel { 
			Foreach(par_factor by 1, k_const by 1 par k_const) { (p,k) => 
				min_dists(p,k) = 256.to[Int]
				label_list(p, k) = 0.to[LabelType]
			}
			Foreach(vote_len by 1 par 1) { v =>
				vote_list(v) = 0.to[Int]
			}
		}
	}



	def knn_vote(knn_set  	: RegFile2[Int],
				 label_list : RegFile2[LabelType],
				 vote_list	: RegFile1[Int]) : LabelType = {

		/* Apply merge-sort first. Each row of knn_set should already be sorted */
		val sorted_label_list = RegFile[LabelType](k_const)
		merge_sorted_lists(knn_set, label_list, sorted_label_list)

		Foreach(sorted_label_list.length by 1){ i =>
			vote_list( sorted_label_list(i).to[I32] ) = vote_list( sorted_label_list(i).to[I32] ) + 1
		}

		val best_label = Reg[IntAndIndex](IntAndIndex(0.to[Int], 0.to[I32]))
		best_label.reset

		Reduce(best_label)(vote_list.length by 1) { j =>
			IntAndIndex(vote_list(j), j)
		} {(l1,l2) => mux(l1.value > l2.value, l1, l2) }

		best_label.inx.to[LabelType]
	}

	def initialize_train_data(training_set_dram_1 : DRAM1[DigitType1], 
							  training_set_dram_2 : DRAM1[DigitType2], 
							  label_set_dram : DRAM1[LabelType], 
							  file_string : String) = {
		/* Rosetta sw version assumes unsigned long long => 64 bits. */

		val training0_dat = loadCSV2D[UInt64]("/home/jcamach2/" + file_string + "DigitRecognition/196data/training_set_0.dat", ",", "\n")
		val training1_dat = loadCSV2D[UInt64]("/home/jcamach2/" + file_string + "DigitRecognition/196data/training_set_1.dat", ",", "\n")
		val training2_dat = loadCSV2D[UInt64]("/home/jcamach2/" + file_string + "DigitRecognition/196data/training_set_2.dat", ",", "\n")
		val training3_dat = loadCSV2D[UInt64]("/home/jcamach2/" + file_string + "DigitRecognition/196data/training_set_3.dat", ",", "\n")
		val training4_dat = loadCSV2D[UInt64]("/home/jcamach2/" + file_string + "DigitRecognition/196data/training_set_4.dat", ",", "\n")	
		val training5_dat = loadCSV2D[UInt64]("/home/jcamach2/" + file_string + "DigitRecognition/196data/training_set_5.dat", ",", "\n")	
		val training6_dat = loadCSV2D[UInt64]("/home/jcamach2/" + file_string + "DigitRecognition/196data/training_set_6.dat", ",", "\n")	
		val training7_dat = loadCSV2D[UInt64]("/home/jcamach2/" + file_string + "DigitRecognition/196data/training_set_7.dat", ",", "\n")	
		val training8_dat = loadCSV2D[UInt64]("/home/jcamach2/" + file_string + "DigitRecognition/196data/training_set_8.dat", ",", "\n")	
		val training9_dat = loadCSV2D[UInt64]("/home/jcamach2/" + file_string + "DigitRecognition/196data/training_set_9.dat", ",", "\n")	

		val training_list = List(training0_dat, training1_dat, training2_dat, training3_dat, training4_dat, training5_dat, 
								 training6_dat, training7_dat, training8_dat, training9_dat)

		val entire_training_vec1	=	training_list.map( training_dat => Array.tabulate(class_size) { i =>
																	 		val sample1 = training_dat.apply(i, 0).to[UInt64]
																			val sample2 = training_dat.apply(i, 1).to[UInt64]
																			DigitType1(sample1, sample2)
																		} 
													 ).reduce(_ ++ _)

		val entire_training_vec2	=	training_list.map( training_dat => Array.tabulate(class_size) { i =>
																			val sample3 = training_dat.apply(i, 2).to[UInt64]
																			val sample4 = training_dat.apply(i, 3).to[UInt64]
																			DigitType2(sample3, sample4)
																		} 
													 ).reduce(_ ++ _)

		//val print_vec = true
		//if (print_vec) { /* this is for making sure that I'm loading the file correctly */
	//		for (i <- 0 until class_size) {
	//			println(entire_training_vec1(i).d1)
	//			println(entire_training_vec1(i).d2)
	//		}
	//	}

		setMem(training_set_dram_1, entire_training_vec1)
		setMem(training_set_dram_2, entire_training_vec2)

		val label_vec = Array.tabulate(training_set_dram_1.length){ i => (i.to[Int] / class_size).to[LabelType] }
		setMem(label_set_dram, label_vec)
	}


	def initialize_test_data(test_set_dram_1 : DRAM1[DigitType1], 
							 test_set_dram_2 : DRAM1[DigitType2], 
							 file_string 	 : String) = {
		val test_dat = loadCSV2D[UInt64]("/home/jcamach2/" + file_string + "DigitRecognition/196data/test_set.dat", ",", "\n")

		val test_actual_vector1 = Array.tabulate(test_set_dram_1.length) { i =>
									val sample1 = test_dat.apply(i, 0).to[UInt64]
									val sample2 = test_dat.apply(i, 1).to[UInt64]
									DigitType1(sample1, sample2)
								}

		val test_actual_vector2 = Array.tabulate(test_set_dram_2.length) { i =>
									val sample3 = test_dat.apply(i, 2).to[UInt64]
									val sample4 = test_dat.apply(i, 3).to[UInt64]
									DigitType2(sample3, sample4)
								}

		setMem(test_set_dram_1, test_actual_vector1)	
		setMem(test_set_dram_2, test_actual_vector2)
	}


	def main(args: Array[String]): Void = {


		val run_on_board = (args(0).to[Int] > 0.to[Int]).as[Boolean]
		val file_string = if (run_on_board) empty_str else simulate_file_string 

		/* input */
		val training_set_dram_1 =  DRAM[DigitType1](num_training)	
		val training_set_dram_2 =  DRAM[DigitType2](num_training)

		val label_set_dram 	  =  DRAM[LabelType](num_training)

		val test_set_dram_1	  =	 DRAM[DigitType1](num_test)
		val test_set_dram_2	  =	 DRAM[DigitType2](num_test)

		/* output */
		val results_dram	  =  DRAM[LabelType](num_test)

		val num_test_local_len 		= 20 // num_test /* for on chip memory */
		val num_train_local_len 	= num_training

		initialize_train_data(training_set_dram_1, training_set_dram_2, label_set_dram, file_string)
		initialize_test_data(test_set_dram_1, test_set_dram_2, file_string)		

		val expected_results = loadCSV1D[LabelType]("/home/jcamach2/" + file_string + "DigitRecognition/196data/expected.dat", "\n")
 
		val test_dram = DRAM[Int](k_const)
		Accel {

			val train_set_local1 	= SRAM[DigitType1](num_train_local_len)
			val train_set_local2 	= SRAM[DigitType2](num_train_local_len)

			val label_set_local 	= SRAM[LabelType](num_train_local_len)

			val test_set_local1 	= SRAM[DigitType1](num_test_local_len)
			val test_set_local2 	= SRAM[DigitType2](num_test_local_len)

			/* ####### Debugging Sorting Functions  #############
			val test_sort = RegFile[Int](par_factor * k_const)
			val test_label = RegFile[LabelType](par_factor * k_const)
			val test_result = RegFile[LabelType](k_const)
			val test_sram = SRAM[Int](k_const)

			Foreach(0 until par_factor * k_const) { i =>
				test_sort(i) =  i.to[Int] + 2.to[Int]
				test_label(i) = i.to[LabelType]
			}
			test_sort(4) = 0.to[Int]

			Pipe { merge_sorted_lists(test_sort, test_label, test_result) }

			Foreach(0 until k_const) { i =>
				test_sram(i) = test_result(i).to[Int]
			}

			test_dram store test_sram 
			*/
		
			Parallel { 
				train_set_local1 load training_set_dram_1 //for now do this
				train_set_local2 load training_set_dram_2

				label_set_local load label_set_dram
			}

			Foreach(num_test by num_test_local_len){ test_factor =>

				Parallel { 
					test_set_local1 load test_set_dram_1(test_factor :: test_factor + num_test_local_len par parLoad)
					test_set_local2 load test_set_dram_2(test_factor :: test_factor + num_test_local_len par parLoad)
				}

				val results_local 	= SRAM[LabelType](num_test_local_len)
				Sequential.Foreach(num_test_local_len by 1) { test_inx =>

					val vote_list		= 	RegFile[Int](10).buffer

					/* initialize KNN */
					val knn_tmp_large_set = RegFile[Int](par_factor, k_const).buffer // when parallelizing, size will need to be k_const * par_factor
					val label_list_tmp 	= 	RegFile[LabelType](par_factor, k_const).buffer

					initialize(knn_tmp_large_set, label_list_tmp, vote_list) 

					/* Training Loop */
					val train_set_par_num = num_train_local_len / par_factor

					val test_local1 = test_set_local1(test_inx)
					val test_local2 = test_set_local2(test_inx)

					Sequential.Foreach(par_factor by 1 par par_factor) { p => 
						Foreach(train_set_par_num by 1) { train_inx =>
							val curr_train_inx = p * train_set_par_num + train_inx
							update_knn(test_local1, test_local2,
									   train_set_local1(curr_train_inx), train_set_local2(curr_train_inx),
									   knn_tmp_large_set, label_list_tmp, p, label_set_local(curr_train_inx)) 
						}
						network_sort(knn_tmp_large_set, label_list_tmp, p) 
					}
					/* Do KNN */
					results_local(test_inx) = knn_vote(knn_tmp_large_set, label_list_tmp, vote_list) 

				}

				results_dram(test_factor :: test_factor + num_test_local_len) store results_local 
			} 

		}

		val result_digits = getMem(results_dram)
    
		/* Test */
		val cksum =	result_digits.zip(expected_results){ (d1,d2) => if (d1 == d2) 1.0 else 0.0 }.reduce{ _ + _ }
		print(cksum)
		println(" out of " + num_test + " correct.")

		val passed = cksum >= 1800.0
		println("Pass? " + passed)
	}


}