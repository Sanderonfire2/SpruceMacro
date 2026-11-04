package com.sprucefarm;

public interface FarmTask {
	enum Status {
		RUNNING,
		DONE,
		FAILED
	}

	Status tick();

	String status();

	String error();

	void cancel();
}
